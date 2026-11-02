//! The computer's clipboard, as text.

use std::sync::Mutex;

use anyhow::{Result, ensure};
use switchboard_core::protocol::MAX_CLIPBOARD;

/// One long-lived handle: on X11 the text stays available only while the
/// process that set it keeps the clipboard open.
#[derive(Default)]
pub struct Clip(Mutex<Option<arboard::Clipboard>>);

impl Clip {
    fn with<T>(&self, f: impl FnOnce(&mut arboard::Clipboard) -> std::result::Result<T, arboard::Error>) -> Result<T> {
        let mut g = self.0.lock().unwrap();
        if g.is_none() {
            *g = Some(arboard::Clipboard::new()?);
        }
        Ok(f(g.as_mut().unwrap())?)
    }

    pub fn get(&self) -> Result<String> {
        let text = match self.with(|c| c.get_text()) {
            Ok(t) => t,
            // Nothing (or something that is not text) on the clipboard reads as empty.
            Err(_) => String::new(),
        };
        ensure!(text.len() <= MAX_CLIPBOARD, "the clipboard is too large to send");
        Ok(text)
    }

    pub fn set(&self, text: &str) -> Result<()> {
        ensure!(text.len() <= MAX_CLIPBOARD, "that text is too large");
        self.with(|c| c.set_text(text.to_owned()))
    }
}
