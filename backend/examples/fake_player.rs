//! A stand-in MPRIS player for testing the media controls without touching a
//! real one. Run it on a private bus:
//!
//!   dbus-daemon --session --fork --print-address   # note the address
//!   DBUS_SESSION_BUS_ADDRESS=<address> cargo run -p switchboard-backend --example fake_player -- cover.png
//!
//! It prints its state after every change, so a test can see what a phone did.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use zbus::interface;
use zbus::zvariant::{ObjectPath, OwnedValue, Value};

struct State {
    playing: bool,
    pos: i64,
    shuffle: bool,
    repeat: String,
    cover: String,
}

fn show(s: &State) {
    println!("state playing={} pos_ms={} shuffle={} loop={}", s.playing, s.pos / 1000, s.shuffle, s.repeat);
}

fn ov<'a>(v: impl Into<Value<'a>>) -> OwnedValue {
    OwnedValue::try_from(v.into()).unwrap()
}

struct Root;

#[interface(name = "org.mpris.MediaPlayer2")]
impl Root {
    #[zbus(property)]
    fn identity(&self) -> String {
        "Fake Player".into()
    }
}

struct Player(Arc<Mutex<State>>);

#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    fn play_pause(&self) {
        let mut s = self.0.lock().unwrap();
        s.playing = !s.playing;
        show(&s);
    }

    fn next(&self) {}

    fn previous(&self) {}

    fn set_position(&self, _track: ObjectPath<'_>, pos: i64) {
        let mut s = self.0.lock().unwrap();
        s.pos = pos;
        show(&s);
    }

    fn seek(&self, offset: i64) {
        let mut s = self.0.lock().unwrap();
        s.pos += offset;
        show(&s);
    }

    #[zbus(property)]
    fn playback_status(&self) -> String {
        if self.0.lock().unwrap().playing { "Playing" } else { "Paused" }.into()
    }

    #[zbus(property)]
    fn position(&self) -> i64 {
        self.0.lock().unwrap().pos
    }

    #[zbus(property)]
    fn can_seek(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn metadata(&self) -> HashMap<String, OwnedValue> {
        let mut m = HashMap::new();
        m.insert("mpris:trackid".into(), ov(ObjectPath::try_from("/fake/track/1").unwrap()));
        m.insert("mpris:length".into(), ov(240_000_000i64));
        m.insert("xesam:title".into(), ov("Fake Song"));
        m.insert("xesam:artist".into(), ov(vec!["Fake Artist".to_string()]));
        m.insert("mpris:artUrl".into(), ov(format!("file://{}", self.0.lock().unwrap().cover)));
        m
    }

    #[zbus(property)]
    fn shuffle(&self) -> bool {
        self.0.lock().unwrap().shuffle
    }

    #[zbus(property)]
    fn set_shuffle(&mut self, v: bool) {
        let mut s = self.0.lock().unwrap();
        s.shuffle = v;
        show(&s);
    }

    #[zbus(property)]
    fn loop_status(&self) -> String {
        self.0.lock().unwrap().repeat.clone()
    }

    #[zbus(property)]
    fn set_loop_status(&mut self, v: String) {
        let mut s = self.0.lock().unwrap();
        s.repeat = v;
        show(&s);
    }
}

#[tokio::main]
async fn main() -> zbus::Result<()> {
    let cover = std::env::args().nth(1).unwrap_or_else(|| "/nonexistent.png".into());
    let st = Arc::new(Mutex::new(State { playing: true, pos: 30_000_000, shuffle: false, repeat: "None".into(), cover }));
    let _c = zbus::connection::Builder::session()?
        .name("org.mpris.MediaPlayer2.fake")?
        .serve_at("/org/mpris/MediaPlayer2", Root)?
        .serve_at("/org/mpris/MediaPlayer2", Player(st.clone()))?
        .build()
        .await?;
    println!("ready");
    show(&st.lock().unwrap());
    std::future::pending::<()>().await;
    Ok(())
}
