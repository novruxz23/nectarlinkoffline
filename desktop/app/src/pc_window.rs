// SPDX-License-Identifier: GPL-3.0-or-later
//! Throw a PC window onto the phone (docs/protocol/mirror.md §7): one window of
//! this PC, at its own size, shown by a paired phone in a floating overlay.
//!
//! The window is captured by itself (Windows.Graphics.Capture, so it keeps
//! streaming behind other windows), encoded as the screen is, and never
//! resized or moved. Up to three windows go at once, each with its own video
//! stream and its own session number. Nothing is captured before the phone
//! has `pc_mirror` consent, and a phone's touch only ever reaches the window
//! it is looking at. A window's title is shown to the phone and never logged.

#[cfg(windows)]
mod imp {
    #![allow(unsafe_code)]

    use std::{
        sync::{
            Arc, Mutex, Once,
            atomic::{AtomicBool, Ordering},
        },
        time::{Duration, Instant},
    };

    use nectarlink_core::{
        DeviceId, LinkState, MirrorConfig, MirrorInput, MirrorSend, MirrorStart, MirrorWindowOpen, NodeEvent,
        PacketKind,
    };
    use windows::{
        Win32::{
            Foundation::{CloseHandle, HWND, LPARAM, MAX_PATH, POINT},
            Graphics::Dwm::{DWMWA_CLOAKED, DwmGetWindowAttribute},
            System::Threading::{
                OpenProcess, PROCESS_NAME_FORMAT, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
            },
            UI::HiDpi::GetDpiForSystem,
            UI::WindowsAndMessaging::{
                EnumWindows, GA_ROOT, GUI_INMOVESIZE, GUITHREADINFO, GWL_EXSTYLE, GetAncestor,
                GetClassNameW, GetCursorPos, GetGUIThreadInfo, GetWindowLongW, GetWindowTextW,
                GetWindowThreadProcessId, IsWindowVisible, WS_EX_TOOLWINDOW,
            },
        },
        core::{BOOL, PWSTR},
    };

    use crate::{
        bridge::app::{request_activation, show_message},
        core_host,
        i18n::tr,
        pc_mirror::{MIN_BITRATE, bgra_to_nv12, new_pc_encoder_with_fallback, remote_input_allowed},
        state::Changes,
        win,
        window_geometry::encoded_size,
    };

    /// Windows a phone shows at once, at most.
    pub const MAX_WINDOWS: usize = nectarlink_core::MIRROR_MAX_WINDOWS;

    const FPS: u32 = 30;
    /// Bits per pixel per frame the video aims for (a window is mostly still).
    const BITS_PER_PIXEL: f64 = 0.12;
    const MAX_BITRATE: u32 = 12_000_000;

    /// A window being shared.
    struct Session {
        id: u32,
        peer: DeviceId,
        window: isize,
        /// The window's app (process name): what the indicator names. Never its title.
        app: String,
        stop: Arc<AtomicBool>,
        /// Set to end this session without telling the phone (it asked).
        quiet: Arc<AtomicBool>,
        force_keyframe: Arc<AtomicBool>,
        /// The phone's overlay is up and the video is going.
        view_only: bool,
    }

    /// A window waiting for the phone's consent (the Allow dialog is up).
    struct Pending {
        peer: DeviceId,
        window: isize,
        app: String,
    }

    static SESSIONS: Mutex<Vec<Session>> = Mutex::new(Vec::new());
    static PENDING: Mutex<Option<Pending>> = Mutex::new(None);
    static NEXT: Mutex<u32> = Mutex::new(nectarlink_core::MIRROR_WINDOW_SESSION_MIN);

    fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A window this PC can share, for the picker. The title is for the person
    /// at this PC and goes nowhere else.
    #[derive(Debug, Clone)]
    pub struct WindowEntry {
        pub window: isize,
        pub title: String,
        pub app: String,
    }

    fn hwnd(window: isize) -> HWND {
        HWND(window as *mut core::ffi::c_void)
    }

    fn title_of(window: isize) -> String {
        let mut buf = [0u16; 256];
        // SAFETY: fills a buffer of that length.
        let len = unsafe { GetWindowTextW(hwnd(window), &mut buf) };
        String::from_utf16_lossy(&buf[..usize::try_from(len).unwrap_or(0)])
    }

    fn class_of(window: isize) -> String {
        let mut buf = [0u16; 128];
        // SAFETY: fills a buffer of that length.
        let len = unsafe { GetClassNameW(hwnd(window), &mut buf) };
        String::from_utf16_lossy(&buf[..usize::try_from(len).unwrap_or(0)])
    }

    /// The window's app as a key a phone can remember it by: its process name
    /// without `.exe`, held to letters, digits, `.`, `-` and `_`.
    fn app_of(window: isize) -> String {
        let mut pid = 0u32;
        // SAFETY: reads the process id of a window handle.
        unsafe { GetWindowThreadProcessId(hwnd(window), Some(&mut pid)) };
        let mut name = String::new();
        if pid != 0 {
            // SAFETY: opens a handle for a query and closes it again.
            unsafe {
                if let Ok(process) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) {
                    let mut buf = [0u16; MAX_PATH as usize];
                    let mut len = buf.len() as u32;
                    if QueryFullProcessImageNameW(
                        process,
                        PROCESS_NAME_FORMAT(0),
                        PWSTR(buf.as_mut_ptr()),
                        &mut len,
                    )
                    .is_ok()
                    {
                        let path = String::from_utf16_lossy(&buf[..len as usize]);
                        name = path.rsplit(['\\', '/']).next().unwrap_or_default().to_owned();
                    }
                    let _ = CloseHandle(process);
                }
            }
        }
        sanitize_app(&name)
    }

    /// `chrome.exe` is `chrome`; anything that isn't a plain name is `app`.
    pub(crate) fn sanitize_app(name: &str) -> String {
        let stem = name.strip_suffix(".exe").or_else(|| name.strip_suffix(".EXE")).unwrap_or(name);
        let clean: String = stem
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'))
            .take(nectarlink_core::MIRROR_WINDOW_APP_MAX)
            .collect();
        if clean.is_empty() { "app".into() } else { clean }
    }

    /// Whether `window` is an ordinary top-level window a person would call a
    /// window: visible, not hidden away by Windows, not a tool window, and not
    /// the desktop or the taskbar.
    fn is_shareable(window: isize) -> bool {
        let h = hwnd(window);
        // SAFETY: plain queries about a window handle, which may be stale.
        unsafe {
            if !win::window_input::exists(window) || !IsWindowVisible(h).as_bool() {
                return false;
            }
            if GetAncestor(h, GA_ROOT) != h {
                return false;
            }
            if GetWindowLongW(h, GWL_EXSTYLE) as u32 & WS_EX_TOOLWINDOW.0 != 0 {
                return false;
            }
            let mut cloaked = 0u32;
            if DwmGetWindowAttribute(h, DWMWA_CLOAKED, (&raw mut cloaked).cast(), size_of::<u32>() as u32).is_ok()
                && cloaked != 0
            {
                return false;
            }
        }
        !matches!(class_of(window).as_str(), "Progman" | "WorkerW" | "Shell_TrayWnd" | "Shell_SecondaryTrayWnd")
    }

    /// The windows a person can pick to send to the phone.
    pub fn list_windows() -> Vec<WindowEntry> {
        unsafe extern "system" fn each(h: HWND, found: LPARAM) -> BOOL {
            // SAFETY: `found` is the `Vec` `list_windows` passed, alive for the call.
            let found = unsafe { &mut *(found.0 as *mut Vec<isize>) };
            found.push(h.0 as isize);
            BOOL(1)
        }
        let mut all: Vec<isize> = Vec::new();
        // SAFETY: the callback only pushes to `all`, which outlives the call.
        let _ = unsafe { EnumWindows(Some(each), LPARAM((&raw mut all) as isize)) };
        all.into_iter()
            .filter(|w| is_shareable(*w))
            .filter_map(|window| {
                let title = title_of(window);
                (!title.trim().is_empty()).then(|| WindowEntry { window, title, app: app_of(window) })
            })
            .collect()
    }

    /// The phone to send a window to: the first one online that offers
    /// `mirror.window`.
    pub fn phone() -> Option<(DeviceId, String)> {
        let node = core_host::node()?;
        core_host::host().hub.read(|s| {
            s.devices
                .iter()
                .filter(|d| matches!(d.link, LinkState::Online { .. }))
                .find(|d| node.peer_offers(d.id, nectarlink_core::MIRROR_WINDOW))
                .map(|d| (d.id, d.info.name.clone()))
        })
    }

    /// Whether any window is being shared.
    pub fn is_active() -> bool {
        !lock(&SESSIONS).is_empty()
    }

    /// The apps being shared, with the phone each goes to, for the indicator.
    pub fn sharing() -> Vec<(DeviceId, String)> {
        lock(&SESSIONS).iter().map(|s| (s.peer, s.app.clone())).collect()
    }

    /// The app of the window waiting for consent, if one does.
    pub fn pending_app() -> Option<String> {
        lock(&PENDING).as_ref().map(|p| p.app.clone())
    }

    /// Drops the window that waits for consent (the dialog was dismissed).
    pub fn cancel_pending() {
        lock(&PENDING).take();
    }

    /// Sends `window` to the phone `peer`: after consent, which is asked for
    /// the way the whole screen's is.
    pub fn share(peer: DeviceId, window: isize) {
        let Some(node) = core_host::node() else { return };
        if !is_shareable(window) {
            show_message(tr!("PcWindow", "This window can't be sent to the phone."));
            return;
        }
        {
            let sessions = lock(&SESSIONS);
            if sessions.iter().any(|s| s.window == window && s.peer == peer) {
                return;
            }
            if sessions.iter().filter(|s| s.peer == peer).count() >= MAX_WINDOWS {
                show_message(tr!("PcWindow", "The phone shows 3 windows at most. Stop one first."));
                return;
            }
        }
        let app = app_of(window);
        if node.pc_mirror_allowed(peer) {
            begin(peer, window, app);
            return;
        }
        *lock(&PENDING) = Some(Pending { peer, window, app });
        // The same dialog as for the whole screen: Not now / Allow / Allow for this device.
        let options = MirrorStart {
            max_size: 0,
            fps: FPS,
            bitrate: 0,
            audio: false,
            session: nectarlink_core::MIRROR_SCREEN,
            app: None,
            stay_awake: false,
            screen_off: false,
            watch: None,
            sound_only: false,
        };
        core_host::host().hub.update(|s| s.apply(&NodeEvent::PcMirrorRequested { device: peer, options }));
        request_activation();
    }

    /// The user allowed the phone: starts the window that waited. False when
    /// none did (so the screen's own request goes ahead).
    pub fn take_pending(peer: DeviceId) -> bool {
        let pending = {
            let mut guard = lock(&PENDING);
            if guard.as_ref().is_some_and(|p| p.peer == peer) { guard.take() } else { None }
        };
        match pending {
            Some(p) => {
                begin(p.peer, p.window, p.app);
                true
            }
            None => false,
        }
    }

    fn next_session() -> Option<u32> {
        let sessions = lock(&SESSIONS);
        let mut next = lock(&NEXT);
        for _ in 0..=(nectarlink_core::MIRROR_WINDOW_SESSION_MAX - nectarlink_core::MIRROR_WINDOW_SESSION_MIN) {
            let id = *next;
            *next = if id >= nectarlink_core::MIRROR_WINDOW_SESSION_MAX {
                nectarlink_core::MIRROR_WINDOW_SESSION_MIN
            } else {
                id + 1
            };
            if sessions.iter().all(|s| s.id != id) {
                return Some(id);
            }
        }
        None
    }

    fn begin(peer: DeviceId, window: isize, app: String) {
        let Some(id) = next_session() else { return };
        let view_only = !remote_input_allowed(peer);
        let stop = Arc::new(AtomicBool::new(false));
        let quiet = Arc::new(AtomicBool::new(false));
        let force_keyframe = Arc::new(AtomicBool::new(false));
        lock(&SESSIONS).push(Session {
            id,
            peer,
            window,
            app: app.clone(),
            stop: stop.clone(),
            quiet: quiet.clone(),
            force_keyframe: force_keyframe.clone(),
            view_only,
        });
        changed();
        let spawned = std::thread::Builder::new().name(format!("pc-window-{id}")).spawn(move || {
            run(peer, id, window, app, view_only, &stop, &quiet, &force_keyframe);
            remove(id);
        });
        if spawned.is_err() {
            remove(id);
        }
    }

    fn remove(id: u32) {
        lock(&SESSIONS).retain(|s| s.id != id);
        // A drag that was going when the window went must not stay held.
        if !is_active() {
            win::window_input::release_held();
        }
        changed();
    }

    /// The indicator and the capabilities follow the sessions.
    fn changed() {
        core_host::host().hub.update(|_| Changes::REMOTE);
        core_host::refresh_capabilities();
    }

    /// The user's Stop: ends every window, telling the phones.
    pub fn stop_all() {
        for s in lock(&SESSIONS).iter() {
            s.stop.store(true, Ordering::Release);
        }
        cancel_pending();
    }

    /// Ends the windows going to `peer` (it left, or `pc_mirror` went off).
    pub fn stop_for(peer: &DeviceId) {
        for s in lock(&SESSIONS).iter().filter(|s| s.peer == *peer) {
            s.stop.store(true, Ordering::Release);
        }
        if lock(&PENDING).as_ref().is_some_and(|p| p.peer == *peer) {
            cancel_pending();
        }
    }

    /// The phone closed one window's overlay (`mirror.stop`): ends it without
    /// telling the phone, which knows. False when it isn't a window session.
    pub fn stop_from_phone(peer: &DeviceId, session: u32) -> bool {
        if !nectarlink_core::is_window_session(session) {
            return false;
        }
        for s in lock(&SESSIONS).iter().filter(|s| s.peer == *peer && s.id == session) {
            s.quiet.store(true, Ordering::Release);
            s.stop.store(true, Ordering::Release);
        }
        true
    }

    /// The phone wants a new keyframe of one window.
    pub fn request_keyframe(peer: &DeviceId, session: u32) -> bool {
        if !nectarlink_core::is_window_session(session) {
            return false;
        }
        for s in lock(&SESSIONS).iter().filter(|s| s.peer == *peer && s.id == session) {
            s.force_keyframe.store(true, Ordering::Release);
        }
        true
    }

    /// Touch, scroll and keys from a phone's overlay. Only for the window of
    /// that session, only with `remote_input` on for that phone. False when
    /// the session isn't a window's (the caller goes on to the screen).
    pub fn handle_input(peer: &DeviceId, session: u32, input: &MirrorInput) -> bool {
        if !nectarlink_core::is_window_session(session) {
            return false;
        }
        let window = lock(&SESSIONS)
            .iter()
            .find(|s| s.peer == *peer && s.id == session && !s.view_only)
            .map(|s| s.window);
        let Some(window) = window else { return true };
        if !remote_input_allowed(*peer) {
            return true;
        }
        let _ = crate::input_share::enter_remote(*peer);
        win::browser::use_physical_pixels();
        match input {
            MirrorInput::Touch { action, x, y } => {
                win::window_input::touch(window, *action, *x, *y);
            }
            MirrorInput::Scroll { x, y, dx, dy } => {
                win::window_input::scroll(window, *x, *y, *dx, *dy);
            }
            MirrorInput::Text { text } => {
                win::window_input::text(window, text);
            }
            MirrorInput::Key { key } => {
                win::window_input::key(window, key);
            }
            MirrorInput::Gamepad { .. } => {}
        }
        true
    }

    pub fn on_event(event: &NodeEvent) {
        if let NodeEvent::LinkChanged { link: LinkState::Online { .. }, .. } = event {
            start_drag_watcher();
        }
        if let NodeEvent::LinkChanged { device, link: LinkState::Offline { .. } } | NodeEvent::DeviceRemoved(device) =
            event
        {
            stop_for(device);
        }
    }

    // ---- Dragging a window onto the Phone zone ----

    /// A window is being dragged, and the pointer is over the Phone zone.
    static DRAG_ACTIVE: AtomicBool = AtomicBool::new(false);
    static DRAG_OVER: AtomicBool = AtomicBool::new(false);
    static WATCHER: Once = Once::new();

    /// The zone's size and gap to the screen's left edge, in device-independent
    /// pixels (`PhoneDropZone.qml` draws the same).
    const ZONE_W: i32 = 112;
    const ZONE_H: i32 = 208;
    const ZONE_GAP: i32 = 12;
    /// The pointer counts as over the zone this much (device-independent
    /// pixels) outside it too, so the drop doesn't have to be exact.
    const ZONE_SLACK: i32 = 24;

    /// Whether a window is being dragged, and whether it is over the zone
    /// (what the UI shows).
    pub fn drag_state() -> (bool, bool) {
        (DRAG_ACTIVE.load(Ordering::Acquire), DRAG_OVER.load(Ordering::Acquire))
    }

    /// The zone on the primary screen's work area, in physical pixels:
    /// left, top, right, bottom.
    fn zone_rect() -> Option<(i32, i32, i32, i32)> {
        let (x, y, w, h) = win::work_area()?;
        // SAFETY: a plain query.
        let scale = f64::from(unsafe { GetDpiForSystem() }) / 96.0;
        let px = |v: i32| (f64::from(v) * scale).round() as i32;
        let left = x + px(ZONE_GAP);
        let top = y + (h - px(ZONE_H)) / 2;
        let _ = w;
        Some((left, top, left + px(ZONE_W), top + px(ZONE_H)))
    }

    /// Whether `pointer` is over (or near) `zone`.
    pub(crate) fn pointer_over_zone(pointer: (i32, i32), zone: (i32, i32, i32, i32), slack: i32) -> bool {
        pointer.0 >= zone.0 - slack
            && pointer.0 <= zone.2 + slack
            && pointer.1 >= zone.1 - slack
            && pointer.1 <= zone.3 + slack
    }

    /// Starts watching for a window being dragged (once). While one is, the
    /// Phone zone shows; letting go of it over the zone sends that window to
    /// the phone. Nothing is hooked: the foreground thread's move/size state
    /// is read a few times a second, and only while a phone is connected.
    fn start_drag_watcher() {
        WATCHER.call_once(|| {
            let _ = std::thread::Builder::new().name("pc-window-drag".into()).spawn(drag_loop);
        });
    }

    fn drag_loop() {
        // Positions in physical pixels, as the capture and the zone are.
        win::browser::use_physical_pixels();
        let mut dragging: Option<isize> = None;
        let mut over = false;
        let mut target: Option<DeviceId> = None;
        let mut last_phone_check = Instant::now() - Duration::from_secs(5);
        loop {
            std::thread::sleep(Duration::from_millis(if dragging.is_some() { 30 } else { 120 }));
            if dragging.is_none() && last_phone_check.elapsed() >= Duration::from_secs(2) {
                target = phone().map(|(id, _)| id);
                last_phone_check = Instant::now();
            }
            let moving = moving_window();
            match (dragging, moving) {
                (None, Some(window)) if target.is_some() && is_shareable(window) && !is_own(window) => {
                    dragging = Some(window);
                    over = false;
                    DRAG_ACTIVE.store(true, Ordering::Release);
                    changed_ui();
                }
                (Some(window), Some(_)) => {
                    let now = match (cursor(), zone_rect()) {
                        (Some(pointer), Some(zone)) => {
                            // SAFETY: a plain query.
                            let scale = f64::from(unsafe { GetDpiForSystem() }) / 96.0;
                            pointer_over_zone(pointer, zone, (f64::from(ZONE_SLACK) * scale) as i32)
                        }
                        _ => false,
                    };
                    if now != over {
                        over = now;
                        DRAG_OVER.store(over, Ordering::Release);
                        changed_ui();
                    }
                    let _ = window;
                }
                (Some(window), None) => {
                    // Let go: over the zone, the window goes to the phone.
                    dragging = None;
                    DRAG_ACTIVE.store(false, Ordering::Release);
                    DRAG_OVER.store(false, Ordering::Release);
                    changed_ui();
                    if over && let Some(phone) = target {
                        share(phone, window);
                    }
                    over = false;
                }
                _ => {}
            }
        }
    }

    /// The window being moved or resized by the user right now, if any.
    fn moving_window() -> Option<isize> {
        let mut info = GUITHREADINFO { cbSize: size_of::<GUITHREADINFO>() as u32, ..Default::default() };
        // SAFETY: fills a GUITHREADINFO for the foreground thread.
        unsafe { GetGUIThreadInfo(0, &mut info) }.ok()?;
        if info.flags.0 & GUI_INMOVESIZE.0 == 0 || info.hwndMoveSize.is_invalid() {
            return None;
        }
        Some(info.hwndMoveSize.0 as isize)
    }

    fn cursor() -> Option<(i32, i32)> {
        let mut at = POINT::default();
        // SAFETY: fills a POINT.
        unsafe { GetCursorPos(&mut at) }.ok()?;
        Some((at.x, at.y))
    }

    /// One of this app's own windows (not worth sending to the phone).
    fn is_own(window: isize) -> bool {
        let mut pid = 0u32;
        // SAFETY: reads the process id of a window handle.
        unsafe { GetWindowThreadProcessId(hwnd(window), Some(&mut pid)) };
        pid == std::process::id()
    }

    /// The Phone zone follows the drag.
    fn changed_ui() {
        core_host::host().hub.update(|_| Changes::REMOTE);
    }

    fn bitrate_for(width: u32, height: u32) -> u32 {
        let wanted = f64::from(width) * f64::from(height) * f64::from(FPS) * BITS_PER_PIXEL;
        (wanted as u32).clamp(MIN_BITRATE * 2, MAX_BITRATE)
    }

    /// What ends a session.
    #[derive(PartialEq, Eq)]
    enum End {
        /// The window is gone.
        Closed,
        /// The user, the phone or a setting ended it, or the link went.
        Stopped,
        /// Something failed before video could start.
        Failed,
    }

    /// The whole life of one shared window, on its own thread.
    #[allow(clippy::too_many_arguments)]
    fn run(
        peer: DeviceId,
        id: u32,
        window: isize,
        app: String,
        view_only: bool,
        stop: &Arc<AtomicBool>,
        quiet: &Arc<AtomicBool>,
        force_keyframe: &Arc<AtomicBool>,
    ) {
        let Some(node) = core_host::node() else { return };
        let runtime = &core_host::host().runtime;
        win::browser::use_physical_pixels();
        let capture = match win::window_capture::WindowCapture::new(hwnd(window)) {
            Ok(capture) => capture,
            Err(error) => {
                tracing::warn!(%peer, %error, "can't capture the window");
                show_message(tr!("PcWindow", "Windows can't capture this window."));
                return;
            }
        };
        let mut native = capture.size();
        if native.0 == 0 || native.1 == 0 {
            return;
        }
        let mut dims = encoded_size(native.0, native.1);
        let open = MirrorWindowOpen {
            session: id,
            app: app.clone(),
            title: title_of(window).chars().take(40).collect(),
            width: dims.0,
            height: dims.1,
            view_only,
        };
        // The phone gets its overlay ready (and says if it can't) before anything is captured.
        match runtime.block_on(node.mirror_window_open(peer, open)) {
            Ok(()) => {}
            Err(nectarlink_core::Error::Unsupported) => {
                show_message(tr!("PcWindow", "Update NectarLink on the phone to show PC windows."));
                return;
            }
            Err(nectarlink_core::Error::Denied) => {
                show_message(tr!(
                    "PcWindow",
                    "The phone can't show the window. Allow \"Display over other apps\" for NectarLink on it."
                ));
                return;
            }
            Err(error) => {
                tracing::warn!(%peer, %error, "the phone didn't take the window");
                return;
            }
        }
        if view_only {
            let _ = runtime.block_on(node.mirror_notice(
                peer,
                id,
                nectarlink_core::MIRROR_NOTICE_WINDOW_VIEW_ONLY,
            ));
        }
        let end = stream(&node, peer, id, window, stop, force_keyframe, capture, &mut dims, &mut native);
        if !quiet.load(Ordering::Acquire) {
            // The overlay goes away: the window closed, or the user stopped sharing.
            let _ = runtime.block_on(node.mirror_window_closed(peer, id));
        }
        if end == End::Failed {
            show_message(tr!("PcWindow", "Couldn't send the window to the phone."));
        }
        tracing::info!(%peer, session = id, "stopped sharing a window");
    }

    #[allow(clippy::too_many_arguments)]
    fn stream(
        node: &nectarlink_core::Node,
        peer: DeviceId,
        id: u32,
        window: isize,
        stop: &Arc<AtomicBool>,
        force_keyframe: &Arc<AtomicBool>,
        mut capture: win::window_capture::WindowCapture,
        dims: &mut (u32, u32),
        native: &mut (u32, u32),
    ) -> End {
        let runtime = &core_host::host().runtime;
        let sender = match runtime.block_on(node.mirror_open(peer)) {
            Ok(sender) => sender,
            Err(error) => {
                tracing::warn!(%peer, %error, "couldn't open the window's video stream");
                return End::Failed;
            }
        };
        // Give the phone's decoder a moment to be ready for the first keyframe.
        std::thread::sleep(Duration::from_millis(120));

        let mut codec = if node.mirror_codecs(peer).is_ok_and(|c| c.iter().any(|c| c == "h265")) {
            "h265".to_owned()
        } else {
            "h264".to_owned()
        };
        let frame_interval = Duration::from_micros(1_000_000 / u64::from(FPS));
        let dur_hns = (10_000_000 / u64::from(FPS)) as i64;
        let mut max_bitrate = bitrate_for(dims.0, dims.1);
        let mut bitrate = max_bitrate;
        let mut bgra = Vec::new();
        let mut nv12 = Vec::new();
        let mut encoder: Option<crate::pc_mirror::ScreenEncoder> = None;
        let started = Instant::now();
        let mut last_reinit = Instant::now() - Duration::from_secs(5);
        let mut last_drop: Option<Instant> = None;
        let mut last_rate_change = Instant::now();
        let mut link_measured = false;
        let mut paused = false;
        let mut end = End::Stopped;

        while !stop.load(Ordering::Relaxed) && !sender.is_closed() {
            let tick = Instant::now();
            if !win::window_input::exists(window) {
                end = End::Closed;
                break;
            }
            // Minimized: no picture to take until it is back.
            let minimized = win::window_input::is_minimized(window);
            if minimized != paused {
                paused = minimized;
                let _ = runtime.block_on(node.mirror_window_state(peer, id, paused));
                if !paused {
                    force_keyframe.store(true, Ordering::SeqCst);
                }
            }
            if paused {
                std::thread::sleep(Duration::from_millis(100));
                continue;
            }

            // The window's own size changed: a new stream config, and the
            // phone is told (it keeps the overlay, changes its shape).
            let now = capture.size();
            if now != *native && now.0 > 0 && now.1 > 0 {
                *native = now;
                let new_dims = encoded_size(now.0, now.1);
                if new_dims != *dims {
                    *dims = new_dims;
                    encoder = None;
                    max_bitrate = bitrate_for(dims.0, dims.1);
                    bitrate = max_bitrate;
                    let _ = runtime.block_on(node.mirror_window_resize(peer, id, dims.0, dims.1));
                }
            }

            let requested_kf = force_keyframe.swap(false, Ordering::SeqCst);
            let keyframe_in_place = requested_kf && encoder.as_ref().is_some_and(|e| e.request_keyframe());
            let need_reinit = encoder.is_none()
                || (requested_kf && !keyframe_in_place && last_reinit.elapsed() >= Duration::from_millis(1400));
            if need_reinit {
                match new_pc_encoder_with_fallback(&mut codec, dims.0, dims.1, FPS, bitrate) {
                    Ok(enc) => {
                        let cfg = MirrorConfig {
                            codec: codec.clone(),
                            width: dims.0,
                            height: dims.1,
                            session: id,
                        };
                        if matches!(sender.send(PacketKind::Config, 0, cfg.to_cbor()), MirrorSend::Closed) {
                            break;
                        }
                        encoder = Some(enc);
                        last_reinit = Instant::now();
                    }
                    Err(error) => {
                        tracing::warn!(%error, codec, "the window's encoder failed to start");
                        end = End::Failed;
                        break;
                    }
                }
            }

            if capture.capture_all(dims.0, dims.1, &mut bgra)
                && let Some(enc) = encoder.as_mut()
            {
                bgra_to_nv12(&bgra, dims.0 as usize, dims.1 as usize, &mut nv12);
                let time_us = started.elapsed().as_micros() as u64;
                match enc.encode(&nv12, (time_us * 10) as i64, dur_hns, stop) {
                    Ok(packets) => {
                        let mut closed = false;
                        for (is_keyframe, bytes) in packets {
                            let kind = if is_keyframe { PacketKind::Keyframe } else { PacketKind::Frame };
                            match sender.send(kind, time_us, bytes) {
                                MirrorSend::Queued | MirrorSend::Dropped => {}
                                MirrorSend::NeedKeyframe => {
                                    last_drop = Some(Instant::now());
                                    force_keyframe.store(true, Ordering::SeqCst);
                                    if !link_measured && last_rate_change.elapsed() >= Duration::from_secs(2) {
                                        let lower = (bitrate / 4 * 3).max(MIN_BITRATE);
                                        if lower < bitrate && enc.set_bitrate(lower) {
                                            bitrate = lower;
                                            last_rate_change = Instant::now();
                                        }
                                    }
                                }
                                MirrorSend::Closed => {
                                    closed = true;
                                    break;
                                }
                            }
                        }
                        if closed {
                            break;
                        }
                        // As the screen does: what the link carries sets the ceiling,
                        // and a clean ten seconds steps back up.
                        if let Some(rate) = sender.link_rate() {
                            link_measured = true;
                            last_drop = Some(Instant::now());
                            if last_rate_change.elapsed() >= Duration::from_secs(1) {
                                let room = u32::try_from(rate / 10 * 7).unwrap_or(u32::MAX);
                                let lower = room.max(bitrate / 2).max(MIN_BITRATE);
                                if lower < bitrate && enc.set_bitrate(lower) {
                                    bitrate = lower;
                                    last_rate_change = Instant::now();
                                }
                            }
                        }
                        let clean = last_drop.is_none_or(|t| t.elapsed() >= Duration::from_secs(10));
                        if clean && bitrate < max_bitrate && last_rate_change.elapsed() >= Duration::from_secs(10)
                        {
                            let higher = (bitrate / 4 * 5).min(max_bitrate);
                            if enc.set_bitrate(higher) {
                                bitrate = higher;
                                last_rate_change = Instant::now();
                            }
                        }
                    }
                    Err(error) => {
                        if stop.load(Ordering::Relaxed) {
                            break;
                        }
                        if codec == "h265" {
                            tracing::warn!(%error, "HEVC window encoding failed; using H.264");
                            codec = "h264".into();
                        } else {
                            tracing::warn!(%error, "window frame encode error; rebuilding the encoder");
                        }
                        encoder = None;
                    }
                }
            }

            if let Some(rest) = frame_interval.checked_sub(tick.elapsed()) {
                std::thread::sleep(rest);
            }
        }
        sender.close();
        end
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn the_pointer_is_over_the_zone_with_a_little_slack() {
        let zone = (12, 400, 124, 608);
        assert!(pointer_over_zone((50, 500), zone, 0));
        assert!(!pointer_over_zone((140, 500), zone, 0));
        assert!(pointer_over_zone((140, 500), zone, 24));
        assert!(!pointer_over_zone((50, 100), zone, 24));
    }

    #[test]
    fn app_keys_are_plain_process_names() {
        assert_eq!(sanitize_app("chrome.exe"), "chrome");
        assert_eq!(sanitize_app("Code.EXE"), "Code");
        assert_eq!(sanitize_app("my app (x86).exe"), "myappx86");
        assert_eq!(sanitize_app(""), "app");
        assert_eq!(sanitize_app("???"), "app");
        assert!(sanitize_app(&"a".repeat(200)).len() <= nectarlink_core::MIRROR_WINDOW_APP_MAX);
    }
}

#[cfg(not(windows))]
mod imp {
    #![allow(dead_code)]

    use nectarlink_core::{DeviceId, MirrorInput, NodeEvent};

    pub const MAX_WINDOWS: usize = 3;

    #[derive(Debug, Clone)]
    pub struct WindowEntry {
        pub window: isize,
        pub title: String,
        pub app: String,
    }

    pub fn list_windows() -> Vec<WindowEntry> {
        Vec::new()
    }
    pub fn phone() -> Option<(DeviceId, String)> {
        None
    }
    pub fn is_active() -> bool {
        false
    }
    pub fn sharing() -> Vec<(DeviceId, String)> {
        Vec::new()
    }
    pub fn pending_app() -> Option<String> {
        None
    }
    pub fn cancel_pending() {}
    pub fn share(_peer: DeviceId, _window: isize) {}
    pub fn take_pending(_peer: DeviceId) -> bool {
        false
    }
    pub fn stop_all() {}
    pub fn stop_for(_peer: &DeviceId) {}
    pub fn stop_from_phone(_peer: &DeviceId, _session: u32) -> bool {
        false
    }
    pub fn request_keyframe(_peer: &DeviceId, _session: u32) -> bool {
        false
    }
    pub fn handle_input(_peer: &DeviceId, _session: u32, _input: &MirrorInput) -> bool {
        false
    }
    pub fn on_event(_event: &NodeEvent) {}
    pub fn drag_state() -> (bool, bool) {
        (false, false)
    }
}

pub use imp::*;
