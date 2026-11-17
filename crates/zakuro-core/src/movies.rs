//! FMV cutscenes, watched so the frontend can show its own copy of one over the game's (ExaltDS's
//! HD cutscenes): which movie file of the RomFS the game is streaming, and how far into it the
//! picture has got.
//!
//! Nothing here touches the game. The game keeps decoding its own movie (its sound, its timing,
//! skipping with Start, the Theater all stay the game's); this only notes two things it does
//! anyway:
//!
//! - its reads of the RomFS (fs.rs `read_file`): the RomFS's own file table, read through the same
//!   view the game reads (with mods over it or not), says which file a read falls in. A read in a
//!   `.moflex` names the movie playing; a read of its first bytes after the movie has been going a
//!   while starts it again (the Theater playing the same movie twice).
//! - its y2r conversions (y2r.rs `convert`): every movie frame goes through y2r on its way to the
//!   screen, so conversions say the picture is moving.
//!
//! The clock is the console's own time (CPU cycles, which the screens' 59.83 Hz and the DSP's
//! 32728 Hz are kept in) since the movie's first frame, gaps of more than MAX_GAP between frames
//! left out. The game paces its movie by that time, so the clock follows it exactly whether
//! emulation runs slow, fast or stops for the sidebar, and it matches the movie's sound, which is
//! what the HD copies are lined up with.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use zakuro_fs::romfs::RomFs;

/// the movie files' extension.
const EXTENSION: &str = ".moflex";
/// the console's clock, cycles a second.
const CYCLES_PER_SECOND: f64 = crate::kernel::thread::CPU_CLOCK_HZ as f64;
/// the longest gap between two frames that counts toward the clock: a longer one is the movie
/// stopped (loading, or the game paused), not playing.
const MAX_GAP: f64 = 0.1;
/// a reread of a movie's first bytes counts as starting it again once this many frames played.
const RESTART_AFTER: u64 = 30;

/// a movie file in a RomFS, where its bytes lie in the level-3 image.
#[derive(Debug, Clone, PartialEq)]
struct Entry {
    start: u64,
    end: u64,
    /// its name without the extension, "MovieOP".
    name: String,
}

/// the movie files of one RomFS view.
struct Index {
    key: (usize, u64),
    entries: Vec<Entry>,
}

#[derive(Debug, Clone)]
struct Playing {
    id: u32,
    name: String,
    /// conversions so far.
    frames: u64,
    /// console time since the first frame, gaps capped at MAX_GAP.
    seconds: f64,
    /// the console's cycles at the last frame.
    cycles: u64,
    /// when the movie was found, and when its last frame came, on the host's clock.
    first: Instant,
    last: Option<Instant>,
}

#[derive(Default)]
struct State {
    indexes: Vec<Index>,
    playing: Option<Playing>,
    next_id: u32,
}

static STATE: Mutex<State> = Mutex::new(State {
    indexes: Vec::new(),
    playing: None,
    next_id: 1,
});

/// a movie the game is playing, as the frontend sees it.
#[derive(Debug, Clone, PartialEq)]
pub struct Snapshot {
    /// a new id for each time a movie starts, the same movie played again included.
    pub id: u32,
    /// the movie file's name without its extension, "Movie04".
    pub name: String,
    /// how far into the movie its picture is.
    pub seconds: f64,
    /// how long since the last frame, or since the movie was found when no frame came yet.
    pub idle: Duration,
    /// whether any frame came yet.
    pub started: bool,
}

/// the movie the game is playing, if it streams one.
pub fn snapshot() -> Option<Snapshot> {
    let state = STATE.lock().ok()?;
    let playing = state.playing.as_ref()?;
    Some(Snapshot {
        id: playing.id,
        name: playing.name.clone(),
        seconds: playing.seconds,
        idle: playing.last.unwrap_or(playing.first).elapsed(),
        started: playing.frames > 0,
    })
}

/// forgets the movie playing, as a loaded state does: the game's own state says what plays next.
pub fn reset() {
    if let Ok(mut state) = STATE.lock() {
        state.playing = None;
    }
}

/// a read the game made of a RomFS view, `key` telling one view from another and `read` reading
/// the view at an offset.
pub fn note_read(
    key: (usize, u64),
    offset: u64,
    count: u64,
    read: impl FnMut(u64, &mut [u8]) -> usize,
) {
    let Ok(mut state) = STATE.lock() else { return };
    if !state.indexes.iter().any(|index| index.key == key) {
        let entries = index(read);
        if !entries.is_empty() {
            log::info!(
                "movies: {} movie files in the RomFS, {}",
                entries.len(),
                entries
                    .iter()
                    .map(|e| e.name.as_str())
                    .collect::<Vec<_>>()
                    .join(" ")
            );
        }
        // a game reads one RomFS, two with an update; a third replaces the oldest
        if state.indexes.len() >= 2 {
            state.indexes.remove(0);
        }
        state.indexes.push(Index { key, entries });
    }
    let Some(index) = state.indexes.iter().find(|index| index.key == key) else {
        return;
    };
    let Some(entry) = hit(&index.entries, offset, count).cloned() else {
        return;
    };
    let restart = match &state.playing {
        Some(playing) if playing.name == entry.name => {
            offset <= entry.start && playing.frames >= RESTART_AFTER
        }
        _ => true,
    };
    if restart {
        let id = state.next_id;
        state.next_id = state.next_id.wrapping_add(1).max(1);
        log::info!("movies: {} starts (#{id})", entry.name);
        state.playing = Some(Playing {
            id,
            name: entry.name,
            frames: 0,
            seconds: 0.0,
            cycles: 0,
            first: Instant::now(),
            last: None,
        });
    }
}

/// a y2r conversion of width x lines pixels finished, the console's clock at `cycles`.
pub fn note_frame(width: usize, lines: usize, cycles: u64) {
    let Ok(mut state) = STATE.lock() else { return };
    let Some(playing) = state.playing.as_mut() else {
        return;
    };
    if playing.frames == 0 {
        log::info!(
            "movies: {}'s first frame, y2r converts {width}x{lines}",
            playing.name
        );
    } else {
        playing.seconds += advance(playing.cycles, cycles);
    }
    playing.frames += 1;
    playing.cycles = cycles;
    playing.last = Some(Instant::now());
}

/// the seconds the clock goes on between frames at cycles `from` and `to`.
fn advance(from: u64, to: u64) -> f64 {
    (to.saturating_sub(from) as f64 / CYCLES_PER_SECOND).min(MAX_GAP)
}

/// the movie a read of count bytes at offset falls in.
fn hit(entries: &[Entry], offset: u64, count: u64) -> Option<&Entry> {
    let end = offset.saturating_add(count.max(1));
    let first = entries.partition_point(|entry| entry.end <= offset);
    entries.get(first).filter(|entry| entry.start < end)
}

/// the movie files of a level-3 RomFS image read through `read`, by where their bytes start.
fn index(mut read: impl FnMut(u64, &mut [u8]) -> usize) -> Vec<Entry> {
    let mut header = [0u8; 0x28];
    if read(0, &mut header) < header.len() {
        return Vec::new();
    }
    let word = |at: usize| {
        u32::from_le_bytes([header[at], header[at + 1], header[at + 2], header[at + 3]])
    };
    if word(0) != 0x28 {
        return Vec::new();
    }
    // the tables lie before the file data
    let data = word(0x24) as usize;
    if !(0x28..=64 << 20).contains(&data) {
        return Vec::new();
    }
    let mut meta = vec![0u8; data];
    if read(0, &mut meta) < data {
        return Vec::new();
    }
    let Ok(romfs) = RomFs::parse_level3(&meta, 0) else {
        return Vec::new();
    };
    let mut entries = Vec::new();
    let Ok(root) = romfs.root() else {
        return entries;
    };
    let mut dirs = vec![root];
    let mut seen = 0usize;
    while let Some(dir) = dirs.pop() {
        seen += 1;
        if seen > 1 << 16 {
            break;
        }
        for (_, file) in romfs.files(&dir) {
            let lower = file.name.to_ascii_lowercase();
            if let Some(stem) = lower.strip_suffix(EXTENSION) {
                let start = romfs.file_data_offset(&file);
                entries.push(Entry {
                    start,
                    end: start + file.data_size,
                    name: file.name[..stem.len()].to_string(),
                });
            }
        }
        dirs.extend(romfs.subdirs(&dir).into_iter().map(|(_, dir)| dir));
    }
    entries.sort_by_key(|entry| entry.start);
    entries
}

#[cfg(test)]
mod tests {
    use super::*;
    use zakuro_fs::romfs_build::{build, BuildFile};

    fn image() -> Vec<u8> {
        let file = |path: &str, size: usize| BuildFile {
            path: path.into(),
            data: vec![0xAB; size],
        };
        build(&[
            file("data/person.bin", 100),
            file("m/MovieOP.moflex", 5000),
            file("m/Movie01.moflex", 3000),
            file("readme.txt", 10),
        ])
    }

    #[test]
    fn finds_the_movie_files_in_a_romfs() {
        let image = image();
        let entries = index(|offset, out: &mut [u8]| {
            let start = (offset as usize).min(image.len());
            let n = out.len().min(image.len() - start);
            out[..n].copy_from_slice(&image[start..start + n]);
            n
        });
        let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names.len(), 2);
        assert!(names.contains(&"MovieOP") && names.contains(&"Movie01"));
        let op = entries.iter().find(|e| e.name == "MovieOP").unwrap();
        assert_eq!(op.end - op.start, 5000);
        assert!(image[op.start as usize..op.end as usize]
            .iter()
            .all(|&b| b == 0xAB));
        assert_eq!(
            hit(&entries, op.start + 10, 4).map(|e| e.name.as_str()),
            Some("MovieOP")
        );
        assert_eq!(hit(&entries, 0, 0x28), None);
    }

    #[test]
    fn the_clock_is_console_time_without_the_pauses() {
        let second = CYCLES_PER_SECOND as u64;
        assert!((advance(0, second / 60) - 1.0 / 60.0).abs() < 1e-7);
        // a pause (the sidebar, a load) adds at most MAX_GAP
        assert_eq!(advance(0, 5 * second), MAX_GAP);
        assert_eq!(advance(10, 5), 0.0);
    }

    #[test]
    fn a_reread_restarts_only_after_the_movie_went_on() {
        let image = image();
        let reader = |offset: u64, out: &mut [u8]| {
            let start = (offset as usize).min(image.len());
            let n = out.len().min(image.len() - start);
            out[..n].copy_from_slice(&image[start..start + n]);
            n
        };
        let entries = index(reader);
        let op = entries
            .iter()
            .find(|e| e.name == "MovieOP")
            .unwrap()
            .clone();
        let key = (0x1234, 0);
        reset();
        note_read(key, op.start, 0x200, reader);
        let first = snapshot().unwrap();
        assert_eq!(first.name, "MovieOP");
        assert!(!first.started);
        // the header read again straight away is the same playback
        note_read(key, op.start, 0x200, reader);
        assert_eq!(snapshot().unwrap().id, first.id);
        let frame = CYCLES_PER_SECOND as u64 / 60;
        for i in 0..60 {
            note_frame(400, 240, 1000 + i * frame);
        }
        let playing = snapshot().unwrap();
        assert!(playing.started);
        assert!(
            (playing.seconds - 59.0 / 60.0).abs() < 1e-6,
            "{}",
            playing.seconds
        );
        note_read(key, op.start + 100, 0x200, reader);
        assert_eq!(snapshot().unwrap().id, first.id);
        note_read(key, op.start, 0x200, reader);
        assert_ne!(snapshot().unwrap().id, first.id);
        reset();
        assert!(snapshot().is_none());
    }
}
