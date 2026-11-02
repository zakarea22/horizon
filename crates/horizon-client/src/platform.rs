//! Startup of the window loop, per platform.
//!
//! Everywhere but Android this is `EventLoop::new`. On Android winit needs the `AndroidApp` the
//! activity glue hands to `android_main` (crates/horizon-android), so that library stores it here
//! with [`set_android_app`] before it starts the game, and the loop is built from it.
use winit::error::EventLoopError;
use winit::event_loop::EventLoop;

#[cfg(target_os = "android")]
mod android {
    use std::sync::OnceLock;
    use winit::platform::android::activity::AndroidApp;

    pub(super) static APP: OnceLock<AndroidApp> = OnceLock::new();

    /// Remember the activity's app handle. Called once, by `android_main`.
    pub fn set_android_app(app: AndroidApp) {
        let _ = APP.set(app);
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
