//! The game as an Android native library.
//!
//! The app in `android/` runs `HorizonGameActivity`, a `NativeActivity` that loads this library.
//! The activity glue (winit's `android-native-activity`) calls [`android_main`] on its own thread;
//! that hands the `AndroidApp` to the game's window loop (`horizon_client::platform`) and runs the
//! game as `horizon-client` does on a desktop.
#![cfg(target_os = "android")]

use std::ffi::{CString, c_char, c_int};
use winit::platform::android::activity::AndroidApp;

/// The same allocator as the desktop executable (`crates/horizon-client/src/main.rs`).
#[global_allocator]
static ALLOCATOR: horizon_threads::Allocator = horizon_threads::ALLOCATOR;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

const LOG_INFO: c_int = 4;
const LOG_ERROR: c_int = 6;

/// A line in `adb logcat` under the tag `Horizon`, before and beside the game's own log file.
fn logcat(priority: c_int, text: &str) {
    if let Ok(text) = CString::new(text.replace('\0', " ")) {
        // SAFETY: both pointers are NUL-terminated strings that outlive the call.
        unsafe { __android_log_write(priority, c"Horizon".as_ptr(), text.as_ptr()) };
    }
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    // A panic would otherwise vanish: Android shows neither stdout nor stderr of a native activity.
    std::panic::set_hook(Box::new(|info| {
        logcat(LOG_ERROR, &format!("panic: {info}"))
    }));
    // The game keeps its folder in HOME and the working directory. The app's folder on shared storage
    // (Android/data/<package>/files) needs no permission and a file manager can put the prepared game
    // files into it; the private folder is the fallback.
    if let Some(dir) = app
        .external_data_path()
        .or_else(|| app.internal_data_path())
    {
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::env::set_current_dir(&dir);
        // SAFETY: nothing else runs yet that reads the environment.
        unsafe { std::env::set_var("HOME", &dir) };
        logcat(LOG_INFO, &format!("data folder: {}", dir.display()));
    }
    horizon_client::platform::set_android_app(app);
    logcat(LOG_INFO, "starting the game");
    horizon_client::entry::main();
    logcat(LOG_INFO, "the game stopped");
}
