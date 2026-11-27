// SPDX-License-Identifier: GPL-3.0-or-later
//! The arithmetic of sharing one PC window with a phone (docs/protocol/mirror.md
//! §7), kept apart from Windows so it can be tested anywhere: the size a window
//! is encoded at, and where a phone's touch lands in the window.

/// The longer side of an encoded window, at most (encoders stop near 4096).
pub const MAX_EDGE: u32 = 3840;

/// The shortest side of an encoded window.
const MIN_EDGE: u32 = 64;

/// The size to encode a `width` × `height` window at: its own size, rounded to
/// the nearest multiple of 16 (what the encoders take), and only scaled down,
/// keeping its shape, when it is bigger than [`MAX_EDGE`]. A window is never
/// resized or moved on the PC for this; only the picture changes, by less than
/// 1.2 % at any size over 640 px.
pub fn encoded_size(width: u32, height: u32) -> (u32, u32) {
    let (mut w, mut h) = (f64::from(width.max(MIN_EDGE)), f64::from(height.max(MIN_EDGE)));
    let longest = w.max(h);
    if longest > f64::from(MAX_EDGE) {
        let scale = f64::from(MAX_EDGE) / longest;
        w *= scale;
        h *= scale;
    }
    let round = |v: f64| ((v / 16.0).round() as u32 * 16).max(MIN_EDGE);
    (round(w), round(h))
}

/// A rectangle in screen pixels: left, top, right, bottom (right and bottom
/// are one past the last pixel, as Windows has them).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Frame {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Frame {
    pub fn width(&self) -> i32 {
        self.right.saturating_sub(self.left)
    }

    pub fn height(&self) -> i32 {
        self.bottom.saturating_sub(self.top)
    }
}

/// The screen pixel at `x`, `y` (fractions of the window, 0 at its left or
/// top, 1 at its right or bottom), always inside `frame`: input for a window
/// never lands outside it, however far off a phone's numbers are. `None` for
/// numbers that aren't numbers, or a window with no size.
pub fn point_in_frame(x: f32, y: f32, frame: Frame) -> Option<(i32, i32)> {
    if !x.is_finite() || !y.is_finite() || frame.width() <= 0 || frame.height() <= 0 {
        return None;
    }
    let along = |fraction: f32, start: i32, len: i32| {
        let span = f64::from(len - 1);
        start + (f64::from(fraction.clamp(0.0, 1.0)) * span).round() as i32
    };
    Some((along(x, frame.left, frame.width()), along(y, frame.top, frame.height())))
}

/// A screen pixel as the 0..=65535 numbers of an absolute mouse event over
/// the whole virtual desktop (`MOUSEEVENTF_VIRTUALDESK`), which starts at
/// `origin` and is `size` pixels across (per axis).
pub fn virtual_desktop_absolute(pixel: (i32, i32), origin: (i32, i32), size: (i32, i32)) -> (i32, i32) {
    let axis = |p: i32, o: i32, s: i32| {
        if s <= 1 {
            return 0;
        }
        let along = i64::from((p - o).clamp(0, s - 1));
        let span = i64::from(s - 1);
        ((along * 65535 + span / 2) / span) as i32
    };
    (axis(pixel.0, origin.0, size.0), axis(pixel.1, origin.1, size.1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_window_keeps_its_shape_to_within_a_few_pixels() {
        assert_eq!(encoded_size(1366, 768), (1360, 768));
        assert_eq!(encoded_size(1920, 1080), (1920, 1088));
        assert_eq!(encoded_size(800, 600), (800, 608));
        // Already a multiple of 16: untouched.
        assert_eq!(encoded_size(1280, 720), (1280, 720));
    }

    #[test]
    fn a_big_window_is_scaled_down_to_the_limit_and_a_tiny_one_stays_encodable() {
        let (w, h) = encoded_size(7680, 4320);
        assert!(w <= MAX_EDGE && h <= MAX_EDGE);
        assert_eq!((w, h), (3840, 2160));
        assert_eq!(encoded_size(0, 0), (64, 64));
        assert_eq!(encoded_size(10, 2000), (64, 2000));
    }

    #[test]
    fn encoded_sizes_are_always_multiples_of_16() {
        for (w, h) in [(1, 1), (333, 777), (1366, 768), (2559, 1439), (5000, 100), (100, 9000)] {
            let (ew, eh) = encoded_size(w, h);
            assert_eq!((ew % 16, eh % 16), (0, 0), "{w}x{h}");
            assert!(ew >= 64 && eh >= 64);
        }
    }

    #[test]
    fn touch_lands_on_the_window_and_never_outside_it() {
        let frame = Frame { left: 100, top: 50, right: 1100, bottom: 850 };
        assert_eq!(point_in_frame(0.0, 0.0, frame), Some((100, 50)));
        // The last pixel inside, not one past it.
        assert_eq!(point_in_frame(1.0, 1.0, frame), Some((1099, 849)));
        assert_eq!(point_in_frame(0.5, 0.5, frame), Some((600, 450)));
        // Far off, or backwards: held to the edge.
        assert_eq!(point_in_frame(-3.0, 9.0, frame), Some((100, 849)));
    }

    #[test]
    fn nonsense_touch_lands_nowhere() {
        let frame = Frame { left: 0, top: 0, right: 100, bottom: 100 };
        assert_eq!(point_in_frame(f32::NAN, 0.5, frame), None);
        assert_eq!(point_in_frame(0.5, f32::INFINITY, frame), None);
        let empty = Frame { left: 10, top: 10, right: 10, bottom: 40 };
        assert_eq!(point_in_frame(0.5, 0.5, empty), None);
        let inverted = Frame { left: 50, top: 0, right: 10, bottom: 10 };
        assert_eq!(point_in_frame(0.5, 0.5, inverted), None);
    }

    #[test]
    fn a_window_on_a_second_monitor_left_of_the_first_maps_inside_the_desktop() {
        // Virtual desktop from -1920 to 1919 across, 1080 high.
        let origin = (-1920, 0);
        let size = (3840, 1080);
        assert_eq!(virtual_desktop_absolute((-1920, 0), origin, size), (0, 0));
        assert_eq!(virtual_desktop_absolute((1919, 1079), origin, size), (65535, 65535));
        let (x, y) = virtual_desktop_absolute((0, 540), origin, size);
        assert!((32_000..=33_600).contains(&x), "{x}");
        assert!((32_000..=33_600).contains(&y), "{y}");
        // Off the desktop: held to its edge.
        assert_eq!(virtual_desktop_absolute((9999, -9999), origin, size), (65535, 0));
        assert_eq!(virtual_desktop_absolute((5, 5), (0, 0), (1, 1)), (0, 0));
    }
}
