//go:build linux

package system

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"

	"github.com/godbus/dbus/v5"

	"switchboard/backend/internal/protocol"
)

// Linux media transport, the counterpart to media_windows.go.
//
// Windows has one system media session and an OS-level arbiter deciding which
// application owns it. Linux has MPRIS2: every player -- Spotify, VLC, mpv,
// Firefox, Chromium, every GNOME and KDE music app -- exports the same D-Bus
// interface under its own bus name, and nothing arbitrates between them. There
// is no "the" session to ask, only a list of players and a choice to make. The
// choice is made in pickPlayer below.
//
// Talking D-Bus directly rather than shelling out to playerctl is the reverse
// of the decision made for audio, for the reason that made it: playerctl is a
// separate package most distributions do not install, whereas D-Bus is the
// session bus the desktop is already built on and godbus speaks it in pure Go.

const (
	mprisPrefix    = "org.mpris.MediaPlayer2."
	mprisPath      = "/org/mpris/MediaPlayer2"
	mprisPlayer    = "org.mpris.MediaPlayer2.Player"
	mprisRoot      = "org.mpris.MediaPlayer2"
	dbusProperties = "org.freedesktop.DBus.Properties"
)

// linuxArtworkLimit caps one cover image, matching the Windows limit: a few
// hundred KB is typical, and the cap is what keeps a malformed or hostile
// artUrl from being read into memory whole.
const linuxArtworkLimit = 4 << 20

var (
	sessionBusOnce sync.Once
	sessionBus     *dbus.Conn
	sessionBusErr  error
)

// errNoPlayer reports a session bus with no MPRIS player on it. It is not an
// error the caller surfaces: a host with nothing playing yields the zero state
// and no error, so clients tell "idle" from "broken".
var errNoPlayer = errors.New("system: no MPRIS player on the session bus")

// bus returns the session bus, opened once per daemon.
//
// The connection is held rather than dialled per call because mediaState is
// read on every host.state broadcast, and a D-Bus handshake is an
// authentication round trip. A daemon started outside a desktop session -- as
// a system unit, over plain ssh -- has no session bus at all, which is the
// error path that turns the media capability off rather than failing calls.
func bus() (*dbus.Conn, error) {
	sessionBusOnce.Do(func() {
		sessionBus, sessionBusErr = dbus.SessionBus()
	})
	if sessionBusErr != nil {
		return nil, fmt.Errorf("system: no session bus: %w", sessionBusErr)
	}
	return sessionBus, nil
}

// mediaSupported reports whether this host can reach a session bus at all.
//
// It deliberately does not require a player to be running. Unlike audio, where
// a missing sink means there is nothing to control ever, a desktop with no
// player open is the normal resting state and will have one a moment later;
// hiding the transport controls until then would make them appear and vanish.
func mediaSupported() bool {
	_, err := bus()
	return err == nil
}

// player is one MPRIS participant, already resolved to its bus name.
type player struct {
	conn *dbus.Conn
	name string
}

func (p player) object() dbus.BusObject {
	return p.conn.Object(p.name, dbus.ObjectPath(mprisPath))
}

// prop reads one property off an MPRIS interface. A player that has just quit
// answers with an error rather than a value, which every caller treats as "not
// playing" instead of propagating.
func (p player) prop(iface, name string) (dbus.Variant, error) {
	var v dbus.Variant
	err := p.object().Call(dbusProperties+".Get", 0, iface, name).Store(&v)
	return v, err
}

func (p player) status() string {
	v, err := p.prop(mprisPlayer, "PlaybackStatus")
	if err != nil {
		return ""
	}
	s, _ := v.Value().(string)
	return s
}

// listPlayers returns every MPRIS player currently on the bus.
func listPlayers(conn *dbus.Conn) ([]player, error) {
	var names []string
	err := conn.BusObject().Call("org.freedesktop.DBus.ListNames", 0).Store(&names)
	if err != nil {
		return nil, fmt.Errorf("system: listing bus names: %w", err)
	}
	players := make([]player, 0, 2)
	for _, name := range names {
		if strings.HasPrefix(name, mprisPrefix) {
			players = append(players, player{conn: conn, name: name})
		}
	}
	// Bus names come back in whatever order the daemon holds them, which is
	// not stable. Sorting makes the tie-break below deterministic, so a host
	// with two idle players does not alternate between them across polls and
	// flicker the now-playing card.
	sort.Slice(players, func(i, j int) bool { return players[i].name < players[j].name })
	return players, nil
}

// pickPlayer chooses which player the transport controls act on.
//
// MPRIS has no notion of a focused or default player, so the rule is the one a
// user would apply looking at their own screen: whatever is making noise. A
// player that is actually Playing wins; failing that, a paused one, which is
// what "resume what I was listening to" means; a Stopped player is chosen only
// when nothing else is open, since an idle Firefox tab should not outrank a
// paused Spotify.
func pickPlayer() (player, error) {
	conn, err := bus()
	if err != nil {
		return player{}, err
	}
	players, err := listPlayers(conn)
	if err != nil {
		return player{}, err
	}
	if len(players) == 0 {
		return player{}, errNoPlayer
	}

	var paused, stopped *player
	for i := range players {
		switch players[i].status() {
		case "Playing":
			return players[i], nil
		case "Paused":
			if paused == nil {
				paused = &players[i]
			}
		default:
			if stopped == nil {
				stopped = &players[i]
			}
		}
	}
	if paused != nil {
		return *paused, nil
	}
	return *stopped, nil
}

// mprisMethods maps protocol transport actions to MPRIS method names.
//
// It doubles as the set of actions this platform accepts, so nothing outside
// this table can be called on a player over the wire.
//
// Unlike Windows, which has only a single PlayPause media key, MPRIS exposes
// Play and Pause separately. They are mapped through rather than folded into
// PlayPause because the phone sends the state it wants: a "pause" frame that
// arrives twice must leave the player paused, not toggle it back into playing.
var mprisMethods = map[string]string{
	"play":   "Play",
	"pause":  "Pause",
	"toggle": "PlayPause",
	"next":   "Next",
	"prev":   "Previous",
	"stop":   "Stop",
}

func sendMediaCommand(action string) error {
	method, ok := mprisMethods[action]
	if !ok {
		return fmt.Errorf("media: unsupported action %q", action)
	}
	p, err := pickPlayer()
	if err != nil {
		if errors.Is(err, errNoPlayer) {
			// Nothing is open to receive it. Reporting success would be a lie,
			// but the failure is the host's state rather than a fault, so it
			// carries the reason a user can act on.
			return fmt.Errorf("media: no player is running")
		}
		return err
	}
	call := p.object().Call(mprisPlayer+"."+method, 0)
	if call.Err != nil {
		return fmt.Errorf("media: %s on %s: %w", method, p.name, call.Err)
	}
	return nil
}

// mprisMetadata is the parsed subset of the xesam metadata dictionary.
type mprisMetadata struct {
	title  string
	artist string
	album  string
	artURL string
}

// parseMetadata reads the xesam fields out of the a{sv} a player exports.
//
// Every field is optional and players disagree about types -- xesam:artist is
// specified as a list of strings and is sometimes a bare string, and a web
// player often supplies a title and nothing else. Each is therefore read
// defensively and independently: one odd field must not cost the rest.
func parseMetadata(v dbus.Variant) mprisMetadata {
	raw, ok := v.Value().(map[string]dbus.Variant)
	if !ok {
		return mprisMetadata{}
	}
	var m mprisMetadata
	if s, ok := raw["xesam:title"].Value().(string); ok {
		m.title = s
	}
	switch artist := raw["xesam:artist"].Value().(type) {
	case []string:
		m.artist = strings.Join(artist, ", ")
	case string:
		m.artist = artist
	}
	if m.artist == "" {
		if s, ok := raw["xesam:albumArtist"].Value().(string); ok {
			m.artist = s
		}
	}
	if s, ok := raw["xesam:album"].Value().(string); ok {
		m.album = s
	}
	if s, ok := raw["mpris:artUrl"].Value().(string); ok {
		m.artURL = s
	}
	return m
}

// identity is the player's own human-readable name ("Spotify", "VLC media
// player"), which is what the "from" label on the phone shows. It falls back
// to the bus name suffix, which is at least the binary.
func (p player) identity() string {
	if v, err := p.prop(mprisRoot, "Identity"); err == nil {
		if s, ok := v.Value().(string); ok && s != "" {
			return s
		}
	}
	return strings.TrimPrefix(p.name, mprisPrefix)
}

// playbackStatus maps MPRIS's vocabulary onto the protocol's.
func playbackStatus(s string) string {
	switch s {
	case "Playing":
		return protocol.PlaybackPlaying
	case "Paused":
		return protocol.PlaybackPaused
	default:
		return protocol.PlaybackStopped
	}
}

// linuxTrack identifies what is playing. As on Windows it is derived in one
// place so mediaState and mediaArtwork agree on the artwork ID; were the two
// to drift, a client would fetch artwork under an ID it never sees in a
// snapshot and re-request it forever.
type linuxTrack struct {
	source, title, artist, album string
}

func (t linuxTrack) artworkID() string {
	sum := sha256.Sum256([]byte(strings.Join(
		[]string{t.source, t.title, t.artist, t.album}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

// readTrack pulls the player's status and metadata in one place.
func readTrack(p player) (linuxTrack, mprisMetadata, string) {
	status := p.status()
	var meta mprisMetadata
	if v, err := p.prop(mprisPlayer, "Metadata"); err == nil {
		meta = parseMetadata(v)
	}
	source := p.identity()
	return linuxTrack{
		source: source,
		title:  meta.title,
		artist: meta.artist,
		album:  meta.album,
	}, meta, status
}

func mediaState() (protocol.MediaState, error) {
	p, err := pickPlayer()
	if errors.Is(err, errNoPlayer) {
		return protocol.MediaState{}, nil
	}
	if err != nil {
		return protocol.MediaState{}, err
	}

	track, meta, status := readTrack(p)
	state := protocol.MediaState{
		Active: true,
		Status: playbackStatus(status),
		Title:  track.title,
		Artist: track.artist,
		Album:  track.album,
		Source: track.source,
	}
	if meta.artURL != "" {
		state.ArtworkID = track.artworkID()
	}
	return state, nil
}

func mediaArtwork() (protocol.MediaArtwork, error) {
	p, err := pickPlayer()
	if errors.Is(err, errNoPlayer) {
		return protocol.MediaArtwork{}, nil
	}
	if err != nil {
		return protocol.MediaArtwork{}, err
	}

	track, meta, _ := readTrack(p)
	if meta.artURL == "" {
		return protocol.MediaArtwork{}, nil
	}
	raw, err := readArtURL(meta.artURL)
	if err != nil || len(raw) == 0 {
		// A cover that cannot be read is not a transport failure. The phone
		// renders the card without art rather than showing the track as
		// broken, which is what an empty reply means.
		return protocol.MediaArtwork{}, nil
	}
	return protocol.MediaArtwork{
		ArtworkID: track.artworkID(),
		MimeType:  linuxImageMime(raw),
		Data:      base64.StdEncoding.EncodeToString(raw),
	}, nil
}

// readArtURL fetches a cover image named by mpris:artUrl.
//
// Only local schemes are read. Players that cache their art to disk -- mpv,
// VLC, Rhythmbox, and the browsers for the media they play locally -- use
// file:// and are covered; some, notably Spotify, publish an https:// URL
// pointing at their own CDN instead, and those come back without art.
//
// That is deliberate. Following the URL would have the daemon make an outbound
// request to an address supplied by whatever happens to be on the session bus,
// on behalf of a phone, which is a larger thing to hand a media player than a
// cover thumbnail is worth.
func readArtURL(raw string) ([]byte, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "file" {
		return nil, fmt.Errorf("media: artwork scheme %q not read", u.Scheme)
	}
	f, err := os.Open(u.Path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return io.ReadAll(io.LimitReader(f, linuxArtworkLimit))
}

// linuxImageMime sniffs the container so the client decodes without guessing.
// Players write whatever their own cache holds, so the extension is no guide.
func linuxImageMime(b []byte) string {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return "image/jpeg"
	case len(b) >= 8 && string(b[:8]) == "\x89PNG\r\n\x1a\n":
		return "image/png"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		return "image/webp"
	default:
		return "application/octet-stream"
	}
}
