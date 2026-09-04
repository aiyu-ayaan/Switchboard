//go:build windows

package system

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"

	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/media/control"
	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

// Media control goes through the System Media Transport Controls (SMTC): the
// session Windows itself surfaces in the volume flyout, which Spotify,
// browsers and native players all publish to.
//
// Driving that session directly, rather than synthesising multimedia key
// presses, buys two things the key route cannot give. Play and pause become
// distinct commands, and the session can be *read*, so a client shows what is
// actually playing and whether it is paused instead of a stateless button row.
// The key route survives as a fallback for players that claim a global hotkey
// but publish no session.

// errNoSession reports that nothing currently holds the media session. It is
// an ordinary state, not a failure: the host simply has nothing playing.
var errNoSession = errors.New("media: no active session")

// artworkLimit caps a single cover image. Session thumbnails run to a few
// hundred kilobytes; past this the session is malformed or hostile.
const artworkLimit = 4 << 20

// mediaSupported reports whether this platform can drive media transport.
func mediaSupported() bool { return true }

// mediaState reads the now-playing snapshot. A host with nothing playing
// yields the zero state and no error.
func mediaState() (protocol.MediaState, error) {
	var state protocol.MediaState
	err := onWinRT(func() error {
		session, err := currentSession()
		if errors.Is(err, errNoSession) {
			return nil
		}
		if err != nil {
			return err
		}
		defer session.Release()

		props, err := mediaProperties(session)
		if err != nil {
			return err
		}
		defer props.Release()

		id := trackIdentity(session, props)
		state = protocol.MediaState{
			Active: true,
			Status: playbackStatus(session),
			Title:  id.title,
			Artist: id.artist,
			Album:  id.album,
			Source: id.source,
		}

		// Asking for the thumbnail reference is cheap; reading the bytes
		// behind it is not. Only its presence is recorded here, and the image
		// itself is read when a client asks for this ID.
		if thumb, err := props.GetThumbnail(); err == nil && thumb != nil {
			thumb.Release()
			state.ArtworkID = id.artworkID()
		}
		return nil
	})
	if err != nil {
		return protocol.MediaState{}, err
	}
	return state, nil
}

// mediaArtwork reads the cover image for whatever is playing now. ArtworkID
// comes back empty when the track has no artwork.
func mediaArtwork() (protocol.MediaArtwork, error) {
	var art protocol.MediaArtwork
	err := onWinRT(func() error {
		session, err := currentSession()
		if errors.Is(err, errNoSession) {
			return nil
		}
		if err != nil {
			return err
		}
		defer session.Release()

		props, err := mediaProperties(session)
		if err != nil {
			return err
		}
		defer props.Release()

		thumb, err := props.GetThumbnail()
		if err != nil || thumb == nil {
			return nil
		}
		defer thumb.Release()

		raw, err := readStream(thumb, artworkLimit)
		if err != nil {
			return err
		}
		if len(raw) == 0 {
			return nil
		}

		art = protocol.MediaArtwork{
			ArtworkID: trackIdentity(session, props).artworkID(),
			MimeType:  imageMime(raw),
			Data:      base64.StdEncoding.EncodeToString(raw),
		}
		return nil
	})
	if err != nil {
		return protocol.MediaArtwork{}, err
	}
	return art, nil
}

// sendMediaCommand drives the transport, preferring the session so play and
// pause stay distinct, and falling back to the multimedia keys for players
// that expose no session.
func sendMediaCommand(action string) error {
	if _, ok := mediaKeys[action]; !ok {
		return fmt.Errorf("media: unsupported action %q", action)
	}
	if err := commandSession(action); err == nil {
		return nil
	}
	return sendMediaKey(action)
}

func commandSession(action string) error {
	return onWinRT(func() error {
		session, err := currentSession()
		if err != nil {
			return err
		}
		defer session.Release()

		var op *foundation.IAsyncOperation
		switch action {
		case "play":
			op, err = session.TryPlayAsync()
		case "pause":
			op, err = session.TryPauseAsync()
		case "toggle":
			op, err = session.TryTogglePlayPauseAsync()
		case "next":
			op, err = session.TrySkipNextAsync()
		case "prev":
			op, err = session.TrySkipPreviousAsync()
		case "stop":
			op, err = session.TryStopAsync()
		default:
			return fmt.Errorf("media: unsupported action %q", action)
		}
		if err != nil {
			return err
		}
		_, err = awaitOp(op)
		return err
	})
}

// ---- Session access ----

func currentSession() (*control.GlobalSystemMediaTransportControlsSession, error) {
	op, err := control.GlobalSystemMediaTransportControlsSessionManagerRequestAsync()
	if err != nil {
		return nil, fmt.Errorf("media: session manager: %w", err)
	}
	ptr, err := awaitOp(op)
	if err != nil {
		return nil, err
	}
	if ptr == nil {
		return nil, errNoSession
	}
	manager := (*control.GlobalSystemMediaTransportControlsSessionManager)(ptr)
	defer manager.Release()

	session, err := manager.GetCurrentSession()
	if err != nil {
		return nil, fmt.Errorf("media: current session: %w", err)
	}
	if session == nil {
		return nil, errNoSession
	}
	return session, nil
}

func mediaProperties(session *control.GlobalSystemMediaTransportControlsSession) (
	*control.GlobalSystemMediaTransportControlsSessionMediaProperties, error) {
	op, err := session.TryGetMediaPropertiesAsync()
	if err != nil {
		return nil, fmt.Errorf("media: properties: %w", err)
	}
	ptr, err := awaitOp(op)
	if err != nil {
		return nil, err
	}
	if ptr == nil {
		return nil, errors.New("media: session exposed no properties")
	}
	return (*control.GlobalSystemMediaTransportControlsSessionMediaProperties)(ptr), nil
}

func playbackStatus(session *control.GlobalSystemMediaTransportControlsSession) string {
	info, err := session.GetPlaybackInfo()
	if err != nil || info == nil {
		return protocol.PlaybackStopped
	}
	defer info.Release()

	status, err := info.GetPlaybackStatus()
	if err != nil {
		return protocol.PlaybackStopped
	}
	switch status {
	case control.GlobalSystemMediaTransportControlsSessionPlaybackStatusPlaying:
		return protocol.PlaybackPlaying
	case control.GlobalSystemMediaTransportControlsSessionPlaybackStatusPaused,
		// Opened and Changing both mean a track is loaded but not advancing,
		// which is what a paused transport looks like to a user.
		control.GlobalSystemMediaTransportControlsSessionPlaybackStatusOpened,
		control.GlobalSystemMediaTransportControlsSessionPlaybackStatusChanging:
		return protocol.PlaybackPaused
	default:
		return protocol.PlaybackStopped
	}
}

// ---- Track identity ----

// track is the set of fields identifying what is playing. Deriving it in one
// place keeps mediaState and mediaArtwork agreeing on the artwork ID; were
// they to drift, a client would fetch artwork under an ID it never sees in a
// snapshot and re-request it forever.
type track struct {
	source, title, artist, album string
}

func trackIdentity(session *control.GlobalSystemMediaTransportControlsSession,
	props *control.GlobalSystemMediaTransportControlsSessionMediaProperties) track {
	t := track{source: appName(session)}
	t.title, _ = props.GetTitle()
	t.artist, _ = props.GetArtist()
	t.album, _ = props.GetAlbumTitle()
	if t.artist == "" {
		t.artist, _ = props.GetAlbumArtist()
	}
	return t
}

// artworkID names this track's cover art. It hashes the track identity rather
// than the image bytes, so polling never pays to decode a thumbnail: the
// artwork changes exactly when the track does.
func (t track) artworkID() string {
	sum := sha256.Sum256([]byte(strings.Join(
		[]string{t.source, t.title, t.artist, t.album}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

// appName turns the session owner's app model ID into something worth showing.
// Desktop apps report an executable ("Spotify.exe"); packaged apps report a
// full AUMID whose segment after "!" is the app name.
func appName(session *control.GlobalSystemMediaTransportControlsSession) string {
	id, err := session.GetSourceAppUserModelId()
	if err != nil {
		return ""
	}
	if i := strings.LastIndex(id, "!"); i >= 0 {
		id = id[i+1:]
	}
	return strings.TrimSuffix(id, ".exe")
}

// imageMime sniffs the container so the client decodes without guessing.
func imageMime(b []byte) string {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return "image/jpeg"
	case len(b) >= 8 && string(b[:8]) == "\x89PNG\r\n\x1a\n":
		return "image/png"
	default:
		return "application/octet-stream"
	}
}

// ---- Multimedia key fallback ----

var procKeybdEvent = user32.NewProc("keybd_event")

const (
	vkMediaNextTrack = 0xB0
	vkMediaPrevTrack = 0xB1
	vkMediaStop      = 0xB2
	vkMediaPlayPause = 0xB3

	keyEventExtendedKey = 0x0001
	keyEventKeyUp       = 0x0002
)

// mediaKeys maps protocol actions to virtual key codes, and doubles as the set
// of actions this platform accepts. Play and pause both land on the toggle
// key: Windows exposes no separate play or pause key, which is exactly the
// imprecision the session route avoids.
var mediaKeys = map[string]uintptr{
	"play":   vkMediaPlayPause,
	"pause":  vkMediaPlayPause,
	"toggle": vkMediaPlayPause,
	"next":   vkMediaNextTrack,
	"prev":   vkMediaPrevTrack,
	"stop":   vkMediaStop,
}

func sendMediaKey(action string) error {
	vk, ok := mediaKeys[action]
	if !ok {
		return fmt.Errorf("media: unsupported action %q", action)
	}
	if err := tapKey(vk); err != nil {
		return fmt.Errorf("media: %s: %w", action, err)
	}
	return nil
}

func tapKey(vk uintptr) error {
	if r, _, err := procKeybdEvent.Call(vk, 0, keyEventExtendedKey, 0); r == 0 && err != windows.ERROR_SUCCESS {
		return err
	}
	if r, _, err := procKeybdEvent.Call(vk, 0, keyEventExtendedKey|keyEventKeyUp, 0); r == 0 && err != windows.ERROR_SUCCESS {
		return err
	}
	return nil
}
