// SPDX-License-Identifier: GPL-3.0-or-later
//! Touch, scroll and keys from a phone for one shared PC window
//! (docs/protocol/mirror.md §7). Everything here is held to that window: a
//! touch lands only on a pixel inside its frame, and only when that pixel
//! really belongs to it (nothing in front of it); keys go only to it while it
//! is the foreground window. Anything else is dropped, never sent somewhere
//! else.

#![allow(unsafe_code)]

use std::sync::atomic::{AtomicBool, Ordering};

use nectarlink_core::TouchAction;
use windows::Win32::{
    Foundation::{HWND, POINT, RECT},
    Graphics::Dwm::{DWMWA_EXTENDED_FRAME_BOUNDS, DwmGetWindowAttribute},
    UI::{
        Input::KeyboardAndMouse::{
            INPUT, INPUT_0, INPUT_MOUSE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
            MOUSEEVENTF_MOVE, MOUSEEVENTF_VIRTUALDESK, MOUSEINPUT, SendInput,
        },
        WindowsAndMessaging::{
            BringWindowToTop, GA_ROOT, GetAncestor, GetForegroundWindow, GetSystemMetrics, GetWindowRect,
            IsIconic, IsWindow, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN,
            SM_YVIRTUALSCREEN, SetForegroundWindow, WindowFromPoint,
        },
    },
};

use crate::window_geometry::{Frame, point_in_frame, virtual_desktop_absolute};

/// Whether the left button was pressed for a window and not yet let go.
static HELD: AtomicBool = AtomicBool::new(false);

fn hwnd(window: isize) -> HWND {
    HWND(window as *mut core::ffi::c_void)
}

/// The window exists and is not minimized.
pub fn is_usable(window: isize) -> bool {
    let h = hwnd(window);
    // SAFETY: plain queries about a window handle, which may be stale.
    unsafe { IsWindow(Some(h)).as_bool() && !IsIconic(h).as_bool() }
}

/// Whether the window exists at all (a minimized one still does).
pub fn exists(window: isize) -> bool {
    // SAFETY: a plain query about a window handle, which may be stale.
    unsafe { IsWindow(Some(hwnd(window))).as_bool() }
}

pub fn is_minimized(window: isize) -> bool {
    // SAFETY: a plain query about a window handle, which may be stale.
    unsafe { IsIconic(hwnd(window)).as_bool() }
}

/// The window's frame on the screen, as the capture sees it (without the
/// invisible resize borders Windows adds around it).
pub fn frame(window: isize) -> Option<Frame> {
    let h = hwnd(window);
    let mut rect = RECT::default();
    // SAFETY: fills a RECT the size of the attribute asked for.
    let from_dwm = unsafe {
        DwmGetWindowAttribute(
            h,
            DWMWA_EXTENDED_FRAME_BOUNDS,
            (&raw mut rect).cast(),
            size_of::<RECT>() as u32,
        )
    };
    if from_dwm.is_err() {
        // SAFETY: fills a RECT.
        unsafe { GetWindowRect(h, &mut rect) }.ok()?;
    }
    let frame = Frame { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
    (frame.width() > 0 && frame.height() > 0).then_some(frame)
}

fn root(h: HWND) -> HWND {
    // SAFETY: a plain query about a window handle.
    unsafe { GetAncestor(h, GA_ROOT) }
}

/// Whether the screen pixel `at` shows `window` (not a window in front of it).
fn shows_window(window: isize, at: (i32, i32)) -> bool {
    // SAFETY: a plain query about a screen position.
    let found = unsafe { WindowFromPoint(POINT { x: at.0, y: at.1 }) };
    !found.is_invalid() && root(found) == root(hwnd(window))
}

fn is_foreground(window: isize) -> bool {
    // SAFETY: a plain query.
    let front = unsafe { GetForegroundWindow() };
    !front.is_invalid() && root(front) == root(hwnd(window))
}

/// Brings the window to the front (its place and size stay as they are).
/// Windows may refuse; the caller looks again.
fn raise(window: isize) {
    let h = hwnd(window);
    // SAFETY: plain window calls on a handle that may be stale.
    unsafe {
        let _ = BringWindowToTop(h);
        let _ = SetForegroundWindow(h);
    }
}

fn mouse(at: (i32, i32), flags: windows::Win32::UI::Input::KeyboardAndMouse::MOUSE_EVENT_FLAGS) {
    // SAFETY: metrics queries.
    let (origin, size) = unsafe {
        (
            (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN)),
            (GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN)),
        )
    };
    let (dx, dy) = virtual_desktop_absolute(at, origin, size);
    let input = INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx,
                dy,
                mouseData: 0,
                dwFlags: MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK | flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    // SAFETY: one well-formed INPUT.
    unsafe { SendInput(&[input], size_of::<INPUT>() as i32) };
}

/// A touch on the window, `x` and `y` as fractions of it. False when it was
/// dropped (window gone, minimized, or covered at that point).
pub fn touch(window: isize, action: TouchAction, x: f32, y: f32) -> bool {
    if !is_usable(window) {
        release_held();
        return false;
    }
    let Some(at) = frame(window).and_then(|f| point_in_frame(x, y, f)) else {
        return false;
    };
    match action {
        TouchAction::Down => {
            if !shows_window(window, at) {
                raise(window);
                if !shows_window(window, at) {
                    return false;
                }
            }
            HELD.store(true, Ordering::Release);
            mouse(at, MOUSEEVENTF_LEFTDOWN);
            true
        }
        TouchAction::Move => {
            // Only a drag that began on this window: no pointer moving about.
            if !HELD.load(Ordering::Acquire) {
                return false;
            }
            mouse(at, windows::Win32::UI::Input::KeyboardAndMouse::MOUSE_EVENT_FLAGS(0));
            true
        }
        TouchAction::Up => {
            if !HELD.swap(false, Ordering::AcqRel) {
                return false;
            }
            mouse(at, MOUSEEVENTF_LEFTUP);
            true
        }
    }
}

/// A scroll over the point `x`, `y` of the window.
pub fn scroll(window: isize, x: f32, y: f32, dx: f32, dy: f32) -> bool {
    if !is_usable(window) {
        return false;
    }
    let Some(at) = frame(window).and_then(|f| point_in_frame(x, y, f)) else {
        return false;
    };
    if !shows_window(window, at) {
        raise(window);
        if !shows_window(window, at) {
            return false;
        }
    }
    mouse(at, windows::Win32::UI::Input::KeyboardAndMouse::MOUSE_EVENT_FLAGS(0));
    super::input::scroll(dx, dy);
    true
}

/// Keys go only to a window that has the focus; one that doesn't is raised
/// first, and the keys dropped if Windows won't.
fn focus(window: isize) -> bool {
    if !is_usable(window) {
        return false;
    }
    if !is_foreground(window) {
        raise(window);
    }
    is_foreground(window)
}

/// Typed text for the window.
pub fn text(window: isize, text: &str) -> bool {
    if !focus(window) {
        return false;
    }
    super::input::type_text(text);
    true
}

/// A key for the window. Only keys that mean something inside one window:
/// the phone's Home, Recents and the like never reach the PC this way.
pub fn key(window: isize, key: &str) -> bool {
    let name = match key {
        "back" => "escape",
        "enter" | "backspace" | "delete" | "left" | "right" | "up" | "down" | "tab" => key,
        _ => return false,
    };
    if !focus(window) {
        return false;
    }
    super::input::press_key(name, &[]);
    true
}

/// Lets go of the button if a window still holds it down (the window closed,
/// the phone left or the share stopped mid-drag).
pub fn release_held() {
    if HELD.swap(false, Ordering::AcqRel) {
        super::input::release_held_buttons();
    }
}
