//! ExaltDS's HD cutscenes, the native half: what Game.java's HdMovies asks each frame, the movie
//! the game is playing (zakuro_core::movies) and where the top screen is on each display, so it
//! can lay its own copy of the movie over the game's.

use std::sync::atomic::{AtomicU64, Ordering};

use zakuro_gpu::{Shown, Viewport};

/// the top screen's place on the main display (0) and on the second one (1), whole pixels packed
/// x, y, width, height (16 bits each); 0 where it isn't shown.
static TOP: [AtomicU64; 2] = [AtomicU64::new(0), AtomicU64::new(0)];

/// what the runtime placed on a display as it presented, None when that display isn't there.
pub fn placed(external: bool, places: Option<&[(Viewport, Shown)]>) {
    TOP[external as usize].store(places.map_or(0, pack), Ordering::Relaxed);
}

/// the top screen's place among the screens placed, the first picture of it the layout shows.
fn pack(places: &[(Viewport, Shown)]) -> u64 {
    let Some((viewport, _)) = places
        .iter()
        .find(|(_, shown)| matches!(shown, Shown::Top | Shown::Both))
    else {
        return 0;
    };
    let field = |value: f32| (value.round().clamp(0.0, 65535.0) as u64) & 0xFFFF;
    if viewport.width < 1.0 || viewport.height < 1.0 {
        return 0;
    }
    field(viewport.x)
        | field(viewport.y) << 16
        | field(viewport.width) << 32
        | field(viewport.height) << 48
}

fn unpack(packed: u64) -> [f32; 4] {
    [0, 16, 32, 48].map(|shift| ((packed >> shift) & 0xFFFF) as f32)
}

/// the frame HdMovies reads: [id, seconds, idle seconds, started, main x, y, width, height, second
/// x, y, width, height], and the movie's name, None when no movie plays.
pub fn report() -> (Option<String>, [f32; 12]) {
    let mut out = [0f32; 12];
    out[4..8].copy_from_slice(&unpack(TOP[0].load(Ordering::Relaxed)));
    out[8..12].copy_from_slice(&unpack(TOP[1].load(Ordering::Relaxed)));
    let Some(movie) = zakuro_core::movies::snapshot() else {
        return (None, out);
    };
    out[0] = movie.id as f32;
    out[1] = movie.seconds as f32;
    out[2] = movie.idle.as_secs_f32();
    out[3] = f32::from(u8::from(movie.started));
    (Some(movie.name), out)
}

/// The movie playing into `out` (see report), its name returned, null when none plays.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeMovie<'a>(
    env: jni::JNIEnv<'a>,
    _: jni::objects::JObject<'a>,
    out: jni::objects::JFloatArray<'a>,
) -> jni::sys::jstring {
    let (name, values) = report();
    let len = (env.get_array_length(&out).unwrap_or(0).max(0) as usize).min(values.len());
    let _ = env.set_float_array_region(&out, 0, &values[..len]);
    match name.and_then(|name| env.new_string(name).ok()) {
        Some(name) => name.into_raw(),
        None => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_top_screen_place_goes_through_packed() {
        let top = Viewport {
            x: 0.0,
            y: 0.0,
            width: 1920.0,
            height: 1080.0,
        };
        let bottom = Viewport {
            x: 10.0,
            y: 20.0,
            width: 320.0,
            height: 240.0,
        };
        assert_eq!(
            unpack(pack(&[(bottom, Shown::Bottom), (top, Shown::Top)])),
            [0.0, 0.0, 1920.0, 1080.0]
        );
        let side = Viewport {
            x: 400.5,
            y: 12.0,
            width: 800.0,
            height: 480.0,
        };
        assert_eq!(
            unpack(pack(&[(side, Shown::Both)])),
            [401.0, 12.0, 800.0, 480.0]
        );
        assert_eq!(pack(&[(bottom, Shown::Bottom)]), 0);
        placed(true, None);
        assert_eq!(report().1[8..12], [0.0; 4]);
    }
}
