package protocol

// Screen sharing runs in both directions, and the two halves are named apart
// so neither can be mistaken for the other on the wire:
//
//   - screen.* is the desktop shared to the phone. The phone asks, the daemon
//     captures, encodes JPEG and streams frames back; touches on the phone come
//     back as input.point / input.button / input.scroll / input.key.
//   - mirror.* is the phone shared to the desktop. The desktop asks, the phone
//     captures through MediaProjection and streams H.264; mouse and keyboard on
//     the desktop window come back as mirror.input and are performed by an
//     accessibility service on the phone.
//
// See docs/docs/screen-share.md.
const (
	ActionScreenStart = "screen.start" // phone -> desktop: begin streaming this desktop
	ActionScreenStop  = "screen.stop"  // either way: stop streaming
	ActionScreenFrame = "screen.frame" // desktop -> phone: one JPEG frame
	ActionScreenState = "screen.state" // desktop -> phone: why a stream ended or failed

	// Absolute pointer and editing keys, which the remote screen needs and the
	// air mouse never did: a tap on a picture of the screen is a position, not
	// a delta, and typing into it needs Backspace.
	ActionInputPoint = "input.point" // phone -> desktop: absolute pointer move
	ActionInputKey   = "input.key"   // phone -> desktop: tap one named key

	ActionMirrorStart    = "mirror.start"    // desktop -> phone: begin mirroring
	ActionMirrorStop     = "mirror.stop"     // desktop -> phone: stop mirroring
	ActionMirrorKeyframe = "mirror.keyframe" // desktop -> phone: a viewer needs a keyframe now
	ActionMirrorFrame    = "mirror.frame"    // phone -> desktop: one H.264 access unit
	ActionMirrorState    = "mirror.state"    // phone -> desktop: what the mirror is doing
	ActionMirrorInput    = "mirror.input"    // desktop -> phone: a touch, key or navigation
	ActionMirrorApps     = "mirror.apps"     // both ways: ask for / deliver the launcher list
	ActionMirrorLaunch   = "mirror.launch"   // desktop -> phone: bring an app to the front
)

// Named keys input.key accepts. A fixed table, like the shell gestures: this
// is the whole keyboard surface the remote screen exposes beyond text.
const (
	KeyBackspace = "backspace"
	KeyDelete    = "delete"
	KeyEnter     = "enter"
	KeyTab       = "tab"
	KeyEscape    = "escape"
	KeyLeft      = "left"
	KeyRight     = "right"
	KeyUp        = "up"
	KeyDown      = "down"
	KeyHome      = "home"
	KeyEnd       = "end"
	KeyPageUp    = "pageUp"
	KeyPageDown  = "pageDown"
)

// ---- Desktop -> phone ----

// ScreenSettings is what the phone asks the stream to be.
type ScreenSettings struct {
	// MaxWidth caps the encoded width. The phone sends roughly its own long
	// edge: pixels past what it can show are bandwidth spent on nothing.
	MaxWidth int `json:"maxWidth"`
	// Quality is the JPEG quality, 1-100.
	Quality int `json:"quality"`
	// FPS is the frame rate ceiling. Unchanged frames are never resent, so an
	// idle desktop costs nothing whatever this says.
	FPS int `json:"fps"`
	// Display picks what is shared: a ScreenDisplay.ID, ScreenAllDisplays for
	// the whole desktop at once, or empty for the primary monitor. One
	// monitor is the default because three of them side by side shrink to an
	// unreadable strip on a phone, and capturing all of them costs three
	// times the frame rate.
	Display string `json:"display,omitempty"`
}

// ScreenAllDisplays shares every monitor as one picture.
const ScreenAllDisplays = "all"

// ScreenDisplay is one monitor, in pixels relative to the desktop's top-left.
type ScreenDisplay struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	X       int    `json:"x"`
	Y       int    `json:"y"`
	Width   int    `json:"width"`
	Height  int    `json:"height"`
	Primary bool   `json:"primary,omitempty"`
}

// DefaultScreenSettings is legible text on a phone held sideways, at a rate
// that keeps a pointer feeling attached to the finger.
func DefaultScreenSettings() ScreenSettings {
	return ScreenSettings{MaxWidth: 1600, Quality: 70, FPS: 20}
}

// Normalize clamps every field into range, filling zeros with defaults.
func (s ScreenSettings) Normalize() ScreenSettings {
	d := DefaultScreenSettings()
	if s.MaxWidth <= 0 {
		s.MaxWidth = d.MaxWidth
	}
	s.MaxWidth = min(max(s.MaxWidth, 320), 3840)
	if s.Quality <= 0 {
		s.Quality = d.Quality
	}
	s.Quality = min(max(s.Quality, 10), 95)
	if s.FPS <= 0 {
		s.FPS = d.FPS
	}
	s.FPS = min(max(s.FPS, 1), 30)
	return s
}

// ScreenFrame is the metadata for one JPEG of the desktop; the image rides
// beside it as a blob.
type ScreenFrame struct {
	Sequence int64 `json:"seq"`
	// Width and Height are the encoded picture's size.
	Width  int `json:"width"`
	Height int `json:"height"`
	// ScreenWidth and ScreenHeight are the desktop's real size, so the phone
	// can show the user what resolution they are looking at.
	ScreenWidth  int `json:"screenWidth"`
	ScreenHeight int `json:"screenHeight"`
	// CursorX and CursorY are the pointer as fractions of the picture, or -1
	// when it is elsewhere. Captures do not include the cursor; the phone
	// draws it from these.
	CursorX float64 `json:"cursorX"`
	CursorY float64 `json:"cursorY"`
}

// ScreenState tells the phone what is being shared, or why nothing is.
type ScreenState struct {
	Streaming bool   `json:"streaming"`
	Error     string `json:"error,omitempty"`
	// Displays are the monitors the phone can choose between, and Display is
	// the one being shared now (an ID, or ScreenAllDisplays).
	Displays []ScreenDisplay `json:"displays,omitempty"`
	Display  string          `json:"display,omitempty"`
}

// InputPoint moves the pointer to a position on the shared desktop, as
// fractions of its width and height.
type InputPoint struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

// InputKey taps one named key.
type InputKey struct {
	Key string `json:"key"`
}

// ScreenViewer is one phone currently watching this desktop, for the desktop
// UI's indicator.
type ScreenViewer struct {
	DeviceID string         `json:"deviceId"`
	Since    int64          `json:"since"`
	Settings ScreenSettings `json:"settings"`
	FPS      float64        `json:"fps"`
}

// ---- Phone -> desktop ----

// MirrorSettings is what the desktop asks the phone's mirror to be.
type MirrorSettings struct {
	// MaxSize caps the long edge of the encoded picture.
	MaxSize int `json:"maxSize"`
	// Bitrate in bits per second.
	Bitrate int `json:"bitrate"`
	FPS     int `json:"fps"`
}

// DefaultMirrorSettings is sharp text at a rate that scrolls smoothly.
func DefaultMirrorSettings() MirrorSettings {
	return MirrorSettings{MaxSize: 1920, Bitrate: 8_000_000, FPS: 60}
}

// MirrorFrame describes one H.264 access unit from the phone's screen.
type MirrorFrame struct {
	Sequence  int64 `json:"seq"`
	Width     int   `json:"width"`
	Height    int   `json:"height"`
	Timestamp int64 `json:"ts"`
	// Key marks an access unit that decodes on its own; it carries SPS/PPS.
	Key bool `json:"key,omitempty"`
}

// MirrorState is the phone's report of its mirror.
type MirrorState struct {
	Streaming bool `json:"streaming"`
	// AwaitingConsent is set while Android's own "start recording or casting"
	// prompt is on the phone's screen. Nothing can skip it, so the desktop
	// says so instead of looking stuck.
	AwaitingConsent bool `json:"awaitingConsent,omitempty"`
	Width           int  `json:"width,omitempty"`
	Height          int  `json:"height,omitempty"`
	// Control reports whether the accessibility service that performs the
	// desktop's clicks is enabled. Off means the mirror is view-only.
	Control bool `json:"control"`
	// Foreground is the package currently in front, so an app window on the
	// desktop can tell whether its app is the one being shown.
	Foreground string         `json:"foreground,omitempty"`
	Settings   MirrorSettings `json:"settings"`
	Error      string         `json:"error,omitempty"`

	// Filled in by the daemon, never by the phone.
	DeviceID    string  `json:"deviceId,omitempty"`
	FPS         float64 `json:"fps,omitempty"`
	BytesPerSec int64   `json:"bytesPerSec,omitempty"`
}

// Mirror input kinds.
const (
	MirrorTap       = "tap"       // a single touch at Points[0]
	MirrorLongPress = "longPress" // a held touch at Points[0]
	MirrorSwipe     = "swipe"     // one stroke along Points, over Duration
	MirrorScroll    = "scroll"    // wheel: DX/DY in notches at Points[0]
	MirrorText      = "text"      // type Text into the focused field
	MirrorKey       = "key"       // one named key (KeyBackspace, KeyEnter)
	MirrorNav       = "nav"       // one of the Nav* actions
)

// Navigation actions for MirrorNav, the phone's own system buttons.
const (
	NavBack          = "back"
	NavHome          = "home"
	NavRecents       = "recents"
	NavNotifications = "notifications"
	NavQuickSettings = "quickSettings"
	NavLock          = "lock"
	NavScreenshot    = "screenshot"
)

// MirrorPoint is a position on the phone's screen as fractions of its width
// and height, with the time since the stroke began.
type MirrorPoint struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
	T int64   `json:"t,omitempty"` // milliseconds
}

// MirrorInput is one action the desktop wants performed on the phone.
type MirrorInput struct {
	Kind     string        `json:"kind"`
	Points   []MirrorPoint `json:"points,omitempty"`
	Duration int64         `json:"duration,omitempty"` // milliseconds
	DX       float64       `json:"dx,omitempty"`
	DY       float64       `json:"dy,omitempty"`
	Text     string        `json:"text,omitempty"`
	Key      string        `json:"key,omitempty"`
	Nav      string        `json:"nav,omitempty"`
}

// MirrorApp is one launchable app on the phone.
type MirrorApp struct {
	Package string `json:"package"`
	Label   string `json:"label"`
	// Icon is a small PNG, base64. The list is fetched once per session and
	// is a few hundred kilobytes at most, so it travels whole.
	Icon string `json:"icon,omitempty"`
}

// MirrorApps is the phone's launcher list.
type MirrorApps struct {
	Apps []MirrorApp `json:"apps"`
}

// MirrorLaunch asks the phone to bring an app to the front.
type MirrorLaunch struct {
	Package string `json:"package"`
}
