//! A phone as touchpad and keyboard. One thread owns the input handle, since
//! it is not safe to share, and every request waits for its answer.

use std::sync::mpsc;
use std::sync::{Mutex, OnceLock};

use anyhow::{Result, anyhow, ensure};
use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use switchboard_core::protocol::{ButtonAction, KeyName, MAX_TYPED, MouseButton};

pub enum Op {
    Move(i32, i32),
    Button(MouseButton, ButtonAction),
    Scroll(i32, i32),
    Key { key: KeyName, ctrl: bool, alt: bool, shift: bool, meta: bool },
    Text(String),
}

type Job = (Op, mpsc::SyncSender<Result<()>>);

fn key(k: KeyName) -> Key {
    match k {
        KeyName::Enter => Key::Return,
        KeyName::Backspace => Key::Backspace,
        KeyName::Tab => Key::Tab,
        KeyName::Escape => Key::Escape,
        KeyName::Space => Key::Space,
        KeyName::Delete => Key::Delete,
        KeyName::Home => Key::Home,
        KeyName::End => Key::End,
        KeyName::PageUp => Key::PageUp,
        KeyName::PageDown => Key::PageDown,
        KeyName::Up => Key::UpArrow,
        KeyName::Down => Key::DownArrow,
        KeyName::Left => Key::LeftArrow,
        KeyName::Right => Key::RightArrow,
    }
}

fn run(e: &mut Enigo, op: Op) -> Result<()> {
    match op {
        Op::Move(dx, dy) => e.move_mouse(dx, dy, Coordinate::Rel)?,
        Op::Button(b, a) => {
            let b = match b {
                MouseButton::Left => Button::Left,
                MouseButton::Right => Button::Right,
                MouseButton::Middle => Button::Middle,
            };
            let d = match a {
                ButtonAction::Click => Direction::Click,
                ButtonAction::Press => Direction::Press,
                ButtonAction::Release => Direction::Release,
            };
            e.button(b, d)?
        }
        Op::Scroll(dx, dy) => {
            if dy != 0 {
                e.scroll(dy, Axis::Vertical)?;
            }
            if dx != 0 {
                e.scroll(dx, Axis::Horizontal)?;
            }
        }
        Op::Key { key: k, ctrl, alt, shift, meta } => {
            let held: Vec<Key> = [(ctrl, Key::Control), (alt, Key::Alt), (shift, Key::Shift), (meta, Key::Meta)].into_iter().filter(|(on, _)| *on).map(|(_, k)| k).collect();
            for m in &held {
                e.key(*m, Direction::Press)?;
            }
            let r = e.key(key(k), Direction::Click);
            // Never leave a modifier stuck down, even if the key failed.
            for m in held.iter().rev() {
                let _ = e.key(*m, Direction::Release);
            }
            r?
        }
        Op::Text(t) => e.text(&t)?,
    }
    Ok(())
}

fn worker() -> &'static Mutex<mpsc::Sender<Job>> {
    static W: OnceLock<Mutex<mpsc::Sender<Job>>> = OnceLock::new();
    W.get_or_init(|| {
        let (tx, rx) = mpsc::channel::<Job>();
        std::thread::spawn(move || {
            let mut handle: Option<Enigo> = None;
            for (op, reply) in rx {
                if handle.is_none() {
                    match Enigo::new(&Settings::default()) {
                        Ok(e) => handle = Some(e),
                        Err(e) => {
                            let _ = reply.send(Err(anyhow!("cannot use the keyboard and mouse here: {e}")));
                            continue;
                        }
                    }
                }
                let _ = reply.send(run(handle.as_mut().unwrap(), op));
            }
        });
        Mutex::new(tx)
    })
}

/// Blocking; call from the blocking pool.
pub fn send(op: Op) -> Result<()> {
    let (reply, wait) = mpsc::sync_channel(1);
    worker().lock().unwrap().send((op, reply)).map_err(|_| anyhow!("input thread stopped"))?;
    wait.recv().map_err(|_| anyhow!("input thread stopped"))?
}

pub fn clamp_move(dx: i32, dy: i32) -> (i32, i32) {
    (dx.clamp(-2000, 2000), dy.clamp(-2000, 2000))
}

pub fn check_text(t: &str) -> Result<()> {
    ensure!(t.chars().count() <= MAX_TYPED, "that is too much text at once");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn moves_and_text_are_bounded() {
        assert_eq!(clamp_move(99999, -99999), (2000, -2000));
        assert_eq!(clamp_move(3, -4), (3, -4));
        assert!(check_text(&"a".repeat(1000)).is_ok());
        assert!(check_text(&"a".repeat(1001)).is_err());
    }
}
