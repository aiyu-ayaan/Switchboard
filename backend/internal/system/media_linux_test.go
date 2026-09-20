//go:build linux

package system

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/prop"

	"switchboard/backend/internal/protocol"
)

// The media backend is exercised against a real MPRIS player on a real session
// bus, because a fake at the godbus layer would test the library rather than
// the code: what actually breaks here is the shape of what players export --
// xesam:artist arriving as a bare string, a metadata dictionary with only a
// title, a player that vanishes between the name list and the property read --
// and none of that is visible through a stub.
//
// The player is exported by the test itself rather than requiring Spotify to
// be installed, so this runs anywhere a session bus exists and skips where one
// does not.

// fakePlayer is a minimal MPRIS2 participant: the two property interfaces and
// the transport methods, which is the whole surface this backend touches.
type fakePlayer struct {
	t      *testing.T
	conn   *dbus.Conn
	props  *prop.Properties
	called chan string
}

func (f *fakePlayer) Play() *dbus.Error      { f.called <- "Play"; return nil }
func (f *fakePlayer) Pause() *dbus.Error     { f.called <- "Pause"; return nil }
func (f *fakePlayer) PlayPause() *dbus.Error { f.called <- "PlayPause"; return nil }
func (f *fakePlayer) Next() *dbus.Error      { f.called <- "Next"; return nil }
func (f *fakePlayer) Previous() *dbus.Error  { f.called <- "Previous"; return nil }
func (f *fakePlayer) Stop() *dbus.Error      { f.called <- "Stop"; return nil }

// exportFakePlayer puts a player on the session bus for the duration of the
// test and tears it down afterwards, so a failed run cannot leave a phantom
// player behind for the next one.
func exportFakePlayer(t *testing.T, busSuffix, identity, status string, metadata map[string]dbus.Variant) *fakePlayer {
	t.Helper()

	conn, err := dbus.SessionBus()
	if err != nil {
		t.Skipf("no session bus: %v", err)
	}

	f := &fakePlayer{t: t, conn: conn, called: make(chan string, 4)}
	if err := conn.Export(f, dbus.ObjectPath(mprisPath), mprisPlayer); err != nil {
		t.Fatalf("exporting player interface: %v", err)
	}

	f.props, err = prop.Export(conn, dbus.ObjectPath(mprisPath), map[string]map[string]*prop.Prop{
		mprisRoot: {
			"Identity": {Value: identity, Writable: false, Emit: prop.EmitTrue},
		},
		mprisPlayer: {
			"PlaybackStatus": {Value: status, Writable: false, Emit: prop.EmitTrue},
			"Metadata":       {Value: metadata, Writable: false, Emit: prop.EmitTrue},
		},
	})
	if err != nil {
		t.Fatalf("exporting properties: %v", err)
	}

	name := mprisPrefix + busSuffix
	reply, err := conn.RequestName(name, dbus.NameFlagDoNotQueue)
	if err != nil {
		t.Fatalf("requesting %s: %v", name, err)
	}
	if reply != dbus.RequestNameReplyPrimaryOwner {
		t.Fatalf("could not own %s: reply %v", name, reply)
	}
	t.Cleanup(func() { _, _ = conn.ReleaseName(name) })

	// The backend holds its own connection to the same bus; nothing is shared
	// with the test's, which is what makes this an end-to-end exercise.
	return f
}

func trackMetadata(title, album, art string, artists []string) map[string]dbus.Variant {
	m := map[string]dbus.Variant{
		"xesam:title": dbus.MakeVariant(title),
		"xesam:album": dbus.MakeVariant(album),
	}
	if artists != nil {
		m["xesam:artist"] = dbus.MakeVariant(artists)
	}
	if art != "" {
		m["mpris:artUrl"] = dbus.MakeVariant(art)
	}
	return m
}

func TestMediaStateReadsMPRISPlayer(t *testing.T) {
	exportFakePlayer(t, "switchboardtest", "Switchboard Test Player", "Playing",
		trackMetadata("Sixteen Tons", "Greatest Hits", "", []string{"Tennessee Ernie Ford"}))

	state, err := mediaState()
	if err != nil {
		t.Fatalf("mediaState: %v", err)
	}
	if !state.Active {
		t.Fatal("state not active with a player on the bus")
	}
	if state.Status != protocol.PlaybackPlaying {
		t.Errorf("Status = %q, want %q", state.Status, protocol.PlaybackPlaying)
	}
	if state.Title != "Sixteen Tons" {
		t.Errorf("Title = %q", state.Title)
	}
	if state.Artist != "Tennessee Ernie Ford" {
		t.Errorf("Artist = %q", state.Artist)
	}
	if state.Album != "Greatest Hits" {
		t.Errorf("Album = %q", state.Album)
	}
	if state.Source != "Switchboard Test Player" {
		t.Errorf("Source = %q, want the player's Identity", state.Source)
	}
	if state.ArtworkID != "" {
		t.Errorf("ArtworkID = %q for a track with no artUrl, want empty", state.ArtworkID)
	}
}

// TestSendMediaCommandReachesPlayer is the one that would have caught the bug
// this file exists for: the phone's transport buttons did nothing on Linux.
func TestSendMediaCommandReachesPlayer(t *testing.T) {
	f := exportFakePlayer(t, "switchboardtest", "Switchboard Test Player", "Playing",
		trackMetadata("Sixteen Tons", "", "", nil))

	for action, want := range map[string]string{
		"play":   "Play",
		"pause":  "Pause",
		"toggle": "PlayPause",
		"next":   "Next",
		"prev":   "Previous",
		"stop":   "Stop",
	} {
		if err := sendMediaCommand(action); err != nil {
			t.Fatalf("sendMediaCommand(%q): %v", action, err)
		}
		select {
		case got := <-f.called:
			if got != want {
				t.Errorf("action %q invoked %s, want %s", action, got, want)
			}
		default:
			t.Errorf("action %q reached no player", action)
		}
	}

	if err := sendMediaCommand("eject"); err == nil {
		t.Error("an action outside the table was accepted")
	}
}

// TestPickPlayerPrefersPlaying covers the choice MPRIS forces and Windows does
// not: with several players on the bus, the one making noise must win. Picking
// by bus name would put an idle browser tab ahead of a paused music player.
func TestPickPlayerPrefersPlaying(t *testing.T) {
	// The idle one sorts first by name, so a naive pick would choose it.
	exportFakePlayer(t, "aaaidle", "Idle Player", "Stopped", trackMetadata("nothing", "", "", nil))
	exportFakePlayer(t, "zzzplaying", "Loud Player", "Playing", trackMetadata("something", "", "", nil))

	p, err := pickPlayer()
	if err != nil {
		t.Fatalf("pickPlayer: %v", err)
	}
	if got := p.identity(); got != "Loud Player" {
		t.Errorf("picked %q, want the playing player", got)
	}
}

// TestPickPlayerPrefersPausedOverStopped covers the second rank: "resume what
// I was listening to" should not stop at an idle player that sorts earlier.
func TestPickPlayerPrefersPausedOverStopped(t *testing.T) {
	exportFakePlayer(t, "aaaidle", "Idle Player", "Stopped", trackMetadata("nothing", "", "", nil))
	exportFakePlayer(t, "zzzpaused", "Paused Player", "Paused", trackMetadata("something", "", "", nil))

	p, err := pickPlayer()
	if err != nil {
		t.Fatalf("pickPlayer: %v", err)
	}
	if got := p.identity(); got != "Paused Player" {
		t.Errorf("picked %q, want the paused player", got)
	}
}

// TestParseMetadataToleratesPlayerQuirks pins the defensive reads. Players
// disagree about xesam:artist's type and routinely omit fields outright, and
// one odd entry must not cost the rest of the track.
func TestParseMetadataToleratesPlayerQuirks(t *testing.T) {
	cases := []struct {
		name string
		raw  map[string]dbus.Variant
		want mprisMetadata
	}{
		{
			name: "artist as a list, per the spec",
			raw:  map[string]dbus.Variant{"xesam:artist": dbus.MakeVariant([]string{"A", "B"})},
			want: mprisMetadata{artist: "A, B"},
		},
		{
			name: "artist as a bare string, as several players send it",
			raw:  map[string]dbus.Variant{"xesam:artist": dbus.MakeVariant("Solo")},
			want: mprisMetadata{artist: "Solo"},
		},
		{
			name: "albumArtist fills in for a missing artist",
			raw:  map[string]dbus.Variant{"xesam:albumArtist": dbus.MakeVariant("Various")},
			want: mprisMetadata{artist: "Various"},
		},
		{
			name: "title only, as a web player sends it",
			raw:  map[string]dbus.Variant{"xesam:title": dbus.MakeVariant("A Video")},
			want: mprisMetadata{title: "A Video"},
		},
		{
			name: "wrong type is dropped, not fatal",
			raw: map[string]dbus.Variant{
				"xesam:title": dbus.MakeVariant("Kept"),
				"xesam:album": dbus.MakeVariant(int32(7)),
			},
			want: mprisMetadata{title: "Kept"},
		},
		{
			name: "empty dictionary",
			raw:  map[string]dbus.Variant{},
			want: mprisMetadata{},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := parseMetadata(dbus.MakeVariant(tc.raw))
			if got != tc.want {
				t.Errorf("parseMetadata() = %+v, want %+v", got, tc.want)
			}
		})
	}
}

// TestMediaArtworkReadsFileURL covers the one artwork scheme that is followed,
// and asserts the ID matches the snapshot's: a mismatch would have the client
// re-request the same cover forever.
func TestMediaArtworkReadsFileURL(t *testing.T) {
	png := []byte("\x89PNG\r\n\x1a\n" + "fake image body")
	path := filepath.Join(t.TempDir(), "cover.png")
	if err := os.WriteFile(path, png, 0o600); err != nil {
		t.Fatal(err)
	}

	exportFakePlayer(t, "switchboardtest", "Switchboard Test Player", "Playing",
		trackMetadata("Sixteen Tons", "Greatest Hits", "file://"+path, []string{"Tennessee Ernie Ford"}))

	state, err := mediaState()
	if err != nil {
		t.Fatalf("mediaState: %v", err)
	}
	if state.ArtworkID == "" {
		t.Fatal("no ArtworkID for a track with an artUrl")
	}

	art, err := mediaArtwork()
	if err != nil {
		t.Fatalf("mediaArtwork: %v", err)
	}
	if art.ArtworkID != state.ArtworkID {
		t.Errorf("artwork ID %q does not match the snapshot's %q", art.ArtworkID, state.ArtworkID)
	}
	if art.MimeType != "image/png" {
		t.Errorf("MimeType = %q, want image/png", art.MimeType)
	}
	if art.Data == "" {
		t.Error("artwork carries no data")
	}
}

// TestReadArtURLRejectsRemoteSchemes pins the deliberate limit: the daemon does
// not fetch a URL handed to it by whatever is on the session bus.
func TestReadArtURLRejectsRemoteSchemes(t *testing.T) {
	for _, raw := range []string{
		"https://i.scdn.co/image/abc123",
		"http://192.168.1.1/admin",
		"ftp://example.invalid/cover.jpg",
	} {
		if _, err := readArtURL(raw); err == nil {
			t.Errorf("readArtURL(%q) succeeded, want refusal", raw)
		}
	}
}

func TestLinuxImageMime(t *testing.T) {
	cases := []struct {
		name string
		body []byte
		want string
	}{
		{"jpeg", []byte{0xFF, 0xD8, 0xFF, 0xE0, 0, 0}, "image/jpeg"},
		{"png", []byte("\x89PNG\r\n\x1a\nrest"), "image/png"},
		{"webp", []byte("RIFF\x00\x00\x00\x00WEBPVP8 "), "image/webp"},
		{"unknown", []byte("not an image"), "application/octet-stream"},
		{"empty", nil, "application/octet-stream"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := linuxImageMime(tc.body); got != tc.want {
				t.Errorf("linuxImageMime() = %q, want %q", got, tc.want)
			}
		})
	}
}
