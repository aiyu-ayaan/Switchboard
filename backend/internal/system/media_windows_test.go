//go:build windows

package system

import (
	"encoding/base64"
	"testing"

	"switchboard/backend/internal/protocol"
)

// TestMediaStateReadsSession exercises the whole WinRT chain: apartment setup,
// the async wait, session lookup and property reads. It cannot assert on what
// is playing, because that depends on the machine, but a machine with nothing
// playing must report the idle state rather than an error.
func TestMediaStateReadsSession(t *testing.T) {
	state, err := mediaState()
	if err != nil {
		t.Fatalf("mediaState: %v", err)
	}
	if !state.Active {
		if state != (protocol.MediaState{}) {
			t.Fatalf("inactive session carried data: %+v", state)
		}
		t.Skip("no media session on this host")
	}

	switch state.Status {
	case protocol.PlaybackPlaying, protocol.PlaybackPaused, protocol.PlaybackStopped:
	default:
		t.Fatalf("unexpected status %q", state.Status)
	}
	t.Logf("playing %q by %q from %q (%s)", state.Title, state.Artist, state.Source, state.Status)

	if state.ArtworkID == "" {
		return
	}

	// The artwork ID a client caches by must be the one the artwork comes back
	// under, or the client re-fetches the same image on every snapshot.
	art, err := mediaArtwork()
	if err != nil {
		t.Fatalf("mediaArtwork: %v", err)
	}
	if art.ArtworkID != state.ArtworkID {
		t.Fatalf("artwork id %q does not match state id %q", art.ArtworkID, state.ArtworkID)
	}
	raw, err := base64.StdEncoding.DecodeString(art.Data)
	if err != nil {
		t.Fatalf("artwork is not valid base64: %v", err)
	}
	if len(raw) == 0 {
		t.Fatal("artwork id was advertised but no bytes came back")
	}
	if art.MimeType == "application/octet-stream" {
		t.Errorf("unrecognised artwork container, first bytes % x", raw[:min(8, len(raw))])
	}
	t.Logf("artwork %s, %d bytes", art.MimeType, len(raw))
}

func TestImageMimeSniffsContainers(t *testing.T) {
	cases := map[string]struct {
		raw  []byte
		want string
	}{
		"jpeg":  {[]byte{0xFF, 0xD8, 0xFF, 0xE0}, "image/jpeg"},
		"png":   {[]byte("\x89PNG\r\n\x1a\n\x00"), "image/png"},
		"other": {[]byte("GIF89a"), "application/octet-stream"},
		"short": {[]byte{0xFF}, "application/octet-stream"},
	}
	for name, tc := range cases {
		if got := imageMime(tc.raw); got != tc.want {
			t.Errorf("%s: imageMime = %q, want %q", name, got, tc.want)
		}
	}
}

// The artwork ID must survive a track change and must not collide across
// tracks, since clients use it as a cache key.
func TestArtworkIDDistinguishesTracks(t *testing.T) {
	a := track{source: "Spotify", title: "One", artist: "Band", album: "Disc"}
	b := track{source: "Spotify", title: "Two", artist: "Band", album: "Disc"}

	if a.artworkID() != (track{source: "Spotify", title: "One", artist: "Band", album: "Disc"}).artworkID() {
		t.Error("same track produced different ids")
	}
	if a.artworkID() == b.artworkID() {
		t.Error("different tracks produced the same id")
	}
}
