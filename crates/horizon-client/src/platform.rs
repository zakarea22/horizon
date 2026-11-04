//! Startup of the window loop, per platform.
//!
//! Everywhere but Android this is `EventLoop::new`. On Android winit needs the `AndroidApp` the
//! activity glue hands to `android_main` (crates/horizon-android), so that library stores it here
//! with [`set_android_app`] before it starts the game, and the loop is built from it.
use winit::error::EventLoopError;
use winit::event::{DeviceEvent, WindowEvent};
use winit::event_loop::EventLoop;

#[cfg(target_os = "android")]
mod android {
    use std::io::Write;
    use std::sync::OnceLock;
    use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
    use winit::event::{ElementState, MouseButton, Touch, TouchPhase, WindowEvent};
    use winit::platform::android::activity::AndroidApp;

    pub(super) static APP: OnceLock<AndroidApp> = OnceLock::new();

    /// Remember the activity's app handle. Called once, by `android_main`.
    pub fn set_android_app(app: AndroidApp) {
        let _ = APP.set(app);
    }

    /// The finger the pointer follows (its id plus one), or 0 when none is down.
    static FINGER: AtomicU64 = AtomicU64::new(0);

    pub(super) fn touch_to_pointer(touch: &Touch) -> [Option<WindowEvent>; 2] {
        let moved = || WindowEvent::CursorMoved {
            device_id: touch.device_id,
            position: touch.location,
        };
        let button = |state| WindowEvent::MouseInput {
            device_id: touch.device_id,
            state,
            button: MouseButton::Left,
        };
        let mine = touch.id + 1;
        match touch.phase {
            TouchPhase::Started => {
                if FINGER
                    .compare_exchange(0, mine, Ordering::Relaxed, Ordering::Relaxed)
                    .is_ok()
                {
                    [Some(moved()), Some(button(ElementState::Pressed))]
                } else {
                    [None, None]
                }
            }
            TouchPhase::Moved => {
                if FINGER.load(Ordering::Relaxed) == mine {
                    [Some(moved()), None]
                } else {
                    [None, None]
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                if FINGER
                    .compare_exchange(mine, 0, Ordering::Relaxed, Ordering::Relaxed)
                    .is_ok()
                {
                    [Some(moved()), Some(button(ElementState::Released))]
                } else {
                    [None, None]
                }
            }
        }
    }

    const TRACE_LINES: usize = 400;
    static TRACED: AtomicUsize = AtomicUsize::new(0);

    /// One line of the input log, until it holds [`TRACE_LINES`].
    pub(super) fn trace(line: &str) {
        let n = TRACED.fetch_add(1, Ordering::Relaxed);
        if n >= TRACE_LINES {
            return;
        }
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open("input-events.log")
        {
            let _ = writeln!(file, "{n:03} {line}");
        }
    }
}

#[cfg(target_os = "android")]
pub use android::set_android_app;

/// The window loop of the game: winit's `EventLoop::new`, or on Android the loop of the activity.
pub fn new_event_loop() -> Result<EventLoop<()>, EventLoopError> {
    #[cfg(target_os = "android")]
    {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        let app = android::APP
            .get()
            .expect("android_main must call set_android_app before the game starts")
            .clone();
        EventLoop::builder().with_android_app(app).build()
    }
    #[cfg(not(target_os = "android"))]
    {
        EventLoop::new()
    }
}

/// The events a window event stands for. Everywhere but Android, itself. On Android winit reports a
/// finger (and, it seems, a Bluetooth mouse) as `Touch` events, which the game's menus do not read:
/// the first finger on the screen becomes a cursor move and a left button press and release, the way
/// a mouse would send them. Further fingers are ignored until it lifts.
pub fn pointer_events(event: WindowEvent) -> [Option<WindowEvent>; 2] {
    #[cfg(target_os = "android")]
    if let WindowEvent::Touch(touch) = &event {
        return android::touch_to_pointer(touch);
    }
    [Some(event), None]
}

/// Appends a window event to `input-events.log` in the game's folder (Android only, for the first
/// few hundred events), so a person can see what the device really sends.
pub fn trace_window_event(event: &WindowEvent) {
    #[cfg(target_os = "android")]
    if matches!(
        event,
        WindowEvent::Touch(_)
            | WindowEvent::CursorMoved { .. }
            | WindowEvent::CursorEntered { .. }
            | WindowEvent::CursorLeft { .. }
            | WindowEvent::MouseInput { .. }
            | WindowEvent::MouseWheel { .. }
            | WindowEvent::KeyboardInput { .. }
            | WindowEvent::Focused(_)
            | WindowEvent::Ime(_)
    ) {
        android::trace(&format!("window {event:?}"));
    }
    #[cfg(not(target_os = "android"))]
    let _ = event;
}

/// The same for device events (`MouseMotion` is how the game turns the camera).
pub fn trace_device_event(event: &DeviceEvent) {
    #[cfg(target_os = "android")]
    android::trace(&format!("device {event:?}"));
    #[cfg(not(target_os = "android"))]
    let _ = event;
}
