#!/usr/bin/env python3
"""Builds ExaltDS's HD cutscenes from your own Fire Emblem HD Movie Collection Blu-ray.

ExaltDS plays MovieOP.mp4 and Movie01.mp4 ... Movie13.mp4 over the game's own 400x240 movies (see
android/app/src/main/java/io/github/zakuro/android/HdMovies.java). This makes them:

  1. finds each game movie in the rip by its sound: the game's .moflex files carry the same soundtrack as
     the disc, so an audio cross-correlation gives where each one starts, whatever the rip holds (the
     Awakening "Play All" title, a whole-disc title, with or without chapters);
  2. measures how fast the disc runs against the game (the disc is 29.97 fps, the game 30, so the disc
     is 0.1 % slow) from two places in each movie, and retimes the video to the game's pace;
  3. deinterlaces the disc's video when it is interlaced, trims or pads it with black to the game
     movie's exact length, and encodes it, video only, a key frame every second (the app seeks).

Nothing of the game or the disc is shipped with ExaltDS; this runs on your copies.

Needs Python 3.9+, numpy, and FFmpeg 4.4 or newer (for the .moflex demuxer) with ffprobe, on the PATH
or given with --ffmpeg/--ffprobe.

  python build_hd_movies.py RIP.mkv romfs/ -o hd_movies

RIP is your disc rip (MakeMKV's .mkv, an .m2ts, or anything FFmpeg reads); the second argument is the
folder holding the game's .moflex files (any folder above them is fine, it is searched). Copy the
.mp4 files it writes to the handheld and choose them in ExaltDS's sidebar: Mods / textures, Import HD
cutscenes. Add --dry-run to only find and report the movies, --with-audio to keep the game's sound in
the files (to check the timing on a PC; ExaltDS plays them muted either way).
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, asdict
from pathlib import Path

try:
    import numpy as np
except ImportError:  # pragma: no cover
    sys.exit("numpy is needed: pip install numpy")

RATE = 4000  # Hz the soundtracks are compared at
PROBE = 6.0  # seconds of a movie's sound looked for in the rip
PROBES = 7  # probes spread over each movie
AGREE = 0.08  # seconds within which probes agree on a movie's start
NAME = re.compile(r"(?i)movie(op|\d\d)")
# the disc runs at 30000/1001 fps where the game runs at 30: its time is 1.001 times the game's
NTSC = 1001 / 1000
SNAP = 0.0012


@dataclass
class Movie:
    name: str
    path: str
    seconds: float  # the game movie's length
    start: float = 0.0  # where it starts in the rip, seconds from the rip's start
    ratio: float = 1.0  # rip seconds per game second
    score: float = 0.0  # how well the sound matched, 0..1
    track: int = 0  # the movie's audio track that matched the disc's
    agreed: int = 0  # probes that agreed on the start
    frame_rate: float = 60.0  # the game movie's frames a second
    nudge: float = 0.0  # what matching the pictures moved the start by, seconds
    picture: float = 0.0  # how well the pictures matched at the start chosen (mean square difference)
    note: str = ""


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, **kw)


def probe(ffprobe: str, path: str) -> dict:
    out = run([ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", path], capture_output=True).stdout
    return json.loads(out)


def pcm(ffmpeg: str, path: str, stream: str) -> np.ndarray:
    """a stream's sound, mono at RATE Hz, as float32."""
    out = run([ffmpeg, "-v", "error", "-i", path, "-map", stream, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
              capture_output=True).stdout
    return np.frombuffer(out, dtype=np.int16).astype(np.float32)


def movie_seconds(ffprobe: str, path: str) -> tuple[float, float]:
    """a .moflex's length and frame rate, its frames over its rate."""
    out = run([ffprobe, "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries",
               "stream=nb_read_frames,r_frame_rate", "-of", "json", path], capture_output=True).stdout
    stream = json.loads(out)["streams"][0]
    num, den = (int(x) for x in stream["r_frame_rate"].split("/"))
    rate = num / den
    # the opening says 59.831 (the 3DS's refresh) but, like the rest, its frames are 1/60 s apart in its
    # own time, the time its sound and the disc keep: the nominal whole rate is the one to go by
    if abs(rate - round(rate)) < 0.01 * rate:
        rate = float(round(rate))
    return int(stream["nb_read_frames"]) / rate, rate


class Finder:
    """normalized cross-correlation of short probes against one long soundtrack, the long one's FFT kept."""

    def __init__(self, sound: np.ndarray, longest: int):
        self.sound = sound - sound.mean()
        self.size = 1 << int(np.ceil(np.log2(len(sound) + longest)))
        self.spectrum = np.fft.rfft(self.sound, self.size)
        squares = np.concatenate([[0.0], np.cumsum(self.sound.astype(np.float64) ** 2)])
        self.squares = squares

    def find(self, piece: np.ndarray) -> tuple[float, float]:
        """where piece best matches (seconds into the sound) and how well (0..1)."""
        piece = piece - piece.mean()
        norm = float(np.linalg.norm(piece))
        if norm == 0 or len(piece) >= len(self.sound):
            return 0.0, 0.0
        corr = np.fft.irfft(self.spectrum * np.conj(np.fft.rfft(piece, self.size)), self.size)
        valid = len(self.sound) - len(piece) + 1
        corr = corr[:valid]
        energy = np.sqrt(np.maximum(self.squares[len(piece):len(piece) + valid] - self.squares[:valid], 1e-9))
        score = np.abs(corr) / (norm * energy)
        at = int(np.argmax(score))
        return at / RATE, float(score[at])


def loud_windows(sound: np.ndarray, regions: list[tuple[float, float]]) -> list[float]:
    """the start (seconds) of the loudest PROBE-long window within each region (fractions of the sound)."""
    width = int(PROBE * RATE)
    step = RATE // 2
    starts = []
    for lo, hi in regions:
        first = int(lo * len(sound))
        last = max(first, min(len(sound) - width, int(hi * len(sound)) - width))
        best, at = -1.0, first
        for s in range(first, last + 1, step):
            rms = float(np.sqrt(np.mean(sound[s:s + width] ** 2)))
            if rms > best:
                best, at = rms, s
        starts.append(at / RATE)
    return starts


def locate(movie: Movie, tracks: list[np.ndarray], finder: Finder) -> None:
    """finds the movie in the rip: its start, the rip's pace against it, the match's quality.

    Seven probes spread over the movie are each looked for in the rip. Probes that matched the right
    place agree on where the movie starts (at the disc's usual pace); the largest group that agrees
    wins, a stray match (music repeated elsewhere on the disc, a quiet stretch) is left out, and the
    pace is fitted through the group."""
    width = int(PROBE * RATE)
    best = None
    for index, sound in enumerate(tracks):
        if len(sound) < width * 2:
            continue
        regions = [(i / PROBES, (i + 1) / PROBES) for i in range(PROBES)]
        found = [(t,) + finder.find(sound[int(t * RATE):int(t * RATE) + width]) for t in loud_windows(sound, regions)]
        starts = [p - t * NTSC for t, p, _ in found]
        groups = [[j for j, other in enumerate(starts) if abs(other - starts[i]) <= AGREE] for i in range(len(starts))]
        group = max(groups, key=lambda g: (len(g), sum(found[j][2] for j in g)))
        key = (len(group), sum(found[j][2] for j in group))
        if best is None or key > best[0]:
            best = (key, index, [found[j] for j in group])
    if best is None:
        movie.note = "too short to find"
        return
    _, movie.track, agreed = best
    times = np.array([t for t, _, _ in agreed])
    places = np.array([p for _, p, _ in agreed])
    ratio = NTSC
    if len(agreed) >= 2 and times.max() - times.min() > 10:
        ratio = float(np.polyfit(times, places, 1)[0])
    # a disc runs at the game's pace or at the NTSC 1.001; a fit near either is that one, its noise left out
    if abs(ratio - (1.0 + NTSC) / 2) < SNAP:
        ratio = NTSC if ratio > (1.0 + NTSC) / 2 else 1.0
    movie.ratio = ratio
    movie.start = float(np.median(places - times * ratio))
    movie.score = float(np.median([s for _, _, s in agreed]))
    movie.agreed = len(agreed)
    if len(agreed) < 3:
        movie.note = f"only {len(agreed)} of {PROBES} probes agree, check this one"


STANDARD = (24.0, 25.0, 30.0, 48.0, 50.0, 60.0)
SMALL = (160, 96)  # pictures are compared this small, in grey


def pictures(cmd: list[str], filters: str) -> tuple[np.ndarray, list[float]]:
    """frames as small grey pictures, and each one's time when the filters report it (showinfo)."""
    w, h = SMALL
    done = subprocess.run(cmd + ["-vf", f"{filters},scale={w}:{h},format=gray,showinfo", "-f", "rawvideo", "-"],
                          capture_output=True)
    frames = np.frombuffer(done.stdout, np.uint8)
    frames = frames[: len(frames) // (w * h) * (w * h)].reshape(-1, h, w).astype(np.float32)
    times = [float(t) for t in re.findall(rb"pts_time:\s*(-?[0-9.]+)", done.stderr)]
    return frames, times


def refine(args, movie: Movie, interlaced: bool, video_index: int, start_time: float) -> None:
    """moves the start the sound gave to where the rip's pictures match the game's best.

    A rip's sound can sit a little off its pictures (re-encodes often shift it by a frame or two), so
    the pictures settle it: the rip's frames, cut to the game's 5:3, against the game movie's own frames,
    over the busiest eight seconds of its first forty, the start tried every 1/240 s around the sound's."""
    span = min(movie.seconds, 40.0)
    game, _ = pictures([args.ffmpeg, "-v", "info", "-i", movie.path, "-t", f"{span:.3f}", "-map", "0:v:0"], "null")
    if len(game) < 60:
        return
    motion = np.abs(np.diff(game, axis=0)).mean(axis=(1, 2))
    rate = movie.frame_rate
    window = 8.0
    last = max(0, int((len(game) / rate - window) * rate))
    sums = np.convolve(motion, np.ones(int(window * rate)), mode="valid")[: last + 1]
    begin = int(np.argmax(sums)) / rate if len(sums) else 0.0
    reach = 0.3
    seek = movie.start + (begin - reach) * movie.ratio
    pre = "bwdif=mode=send_frame," if interlaced else ""
    rip, times = pictures([args.ffmpeg, "-v", "info", "-copyts", "-ss", f"{max(0.0, seek):.6f}", "-t",
                           f"{(window + 2 * reach) * movie.ratio:.6f}", "-i", args.rip, "-map", f"0:{video_index}"],
                          f"{pre}crop=ih*5/3:ih")
    if len(rip) < 10 or len(times) != len(rip):
        movie.note = (movie.note + "; " if movie.note else "") + "pictures could not be compared"
        return
    times = np.array(times) - start_time
    best = None
    for step in range(-int(reach * 240), int(reach * 240) + 1):
        start = movie.start + step / 240
        index = np.rint((times - start) / movie.ratio * rate).astype(int)
        keep = (index >= 0) & (index < len(game))
        if keep.sum() < len(rip) // 2:
            continue
        cost = float(np.mean((rip[keep] - game[index[keep]]) ** 2))
        if best is None or cost < best[0]:
            best = (cost, start)
    if best is None:
        return
    movie.nudge = best[1] - movie.start
    movie.start, movie.picture = best[1], best[0]


def video_filter(movie: Movie, interlaced: bool, fps: float, height: int | None) -> str:
    parts = []
    if interlaced:
        parts.append("bwdif=mode=send_frame")
    parts.append(f"setpts=(PTS-STARTPTS)/{movie.ratio:.9f}")
    pace = fps * movie.ratio
    pace = min(STANDARD, key=lambda rate: abs(rate - pace)) if min(abs(rate - pace) for rate in STANDARD) < 0.02 else pace
    parts.append(f"fps={pace:.6f}")
    if height:
        parts.append(f"scale=-2:{height}:flags=lanczos")
    # a movie starting before the rip's first frame, or ending after its last, gets black there
    lead = max(0.0, -movie.start / movie.ratio)
    if lead > 0:
        parts.append(f"tpad=start_duration={lead:.6f}:start_mode=add:color=black")
    parts.append("tpad=stop_mode=add:stop_duration=10:color=black")
    parts.append(f"trim=duration={movie.seconds:.6f}")
    parts.append("setpts=PTS-STARTPTS")
    parts.append("format=yuv420p")
    return ",".join(parts)


def encode(args, movie: Movie, interlaced: bool, fps: float, video_index: int, out: Path) -> None:
    start = max(0.0, movie.start)
    span = movie.seconds * movie.ratio + 1.0
    cmd = [args.ffmpeg, "-v", "error", "-y", "-ss", f"{start:.6f}", "-t", f"{span:.6f}", "-i", args.rip]
    if args.with_audio:
        cmd += ["-i", movie.path]
    cmd += ["-map", f"0:{video_index}", "-vf", video_filter(movie, interlaced, fps, args.height)]
    if args.codec == "hevc":
        cmd += ["-c:v", "libx265", "-preset", args.preset, "-crf", str(args.crf), "-tag:v", "hvc1",
                "-x265-params", "keyint=30:min-keyint=1:log-level=error"]
    else:
        cmd += ["-c:v", "libx264", "-preset", args.preset, "-crf", str(args.crf), "-g", "30", "-profile:v", "high"]
    if args.with_audio:
        other = 1 - movie.track
        # the disc is the Japanese release, so the track that matched it is the Japanese one
        cmd += ["-map", f"1:a:{other}?", "-map", f"1:a:{movie.track}", "-c:a", "aac", "-b:a", "128k",
                "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=jpn", "-shortest"]
    else:
        cmd += ["-an"]
    cmd += ["-movflags", "+faststart", str(out)]
    run(cmd)


def clock(seconds: float) -> str:
    sign = "-" if seconds < 0 else ""
    seconds = abs(seconds)
    return f"{sign}{int(seconds // 3600)}:{int(seconds // 60 % 60):02d}:{seconds % 60:06.3f}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("rip", help="your disc rip (.mkv, .m2ts, .mp4 ...)")
    parser.add_argument("moflex", help="the folder with the game's MovieOP.moflex ... Movie13.moflex (searched)")
    parser.add_argument("-o", "--out", default="hd_movies", help="where to write the .mp4 files (default hd_movies)")
    parser.add_argument("--codec", choices=["hevc", "h264"], default="hevc", help="HEVC is smaller; H.264 plays anywhere (default hevc)")
    parser.add_argument("--crf", type=int, help="quality, lower is better (default 20 for HEVC, 18 for H.264)")
    parser.add_argument("--preset", default="medium", help="encoder speed preset (default medium)")
    parser.add_argument("--height", type=int, help="scale to this height (default: the rip's own)")
    parser.add_argument("--deinterlace", choices=["auto", "on", "off"], default="auto")
    parser.add_argument("--rip-audio", type=int, default=0, help="which of the rip's audio streams to match against (default 0, the first)")
    parser.add_argument("--only", nargs="*", help="only these movies, e.g. MovieOP Movie04")
    parser.add_argument("--jobs", type=int, default=2, help="movies encoded at once (default 2)")
    parser.add_argument("--with-audio", action="store_true", help="keep the game's sound in the files, to check them")
    parser.add_argument("--dry-run", action="store_true", help="find the movies and report, encode nothing")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args()
    if args.crf is None:
        args.crf = 20 if args.codec == "hevc" else 18
    for tool in (args.ffmpeg, args.ffprobe):
        if shutil.which(tool) is None and not Path(tool).is_file():
            sys.exit(f"{tool} not found: install FFmpeg 4.4 or newer, or give its path with --ffmpeg/--ffprobe")

    # the game's movies
    movies: dict[str, Movie] = {}
    for path in sorted(Path(args.moflex).rglob("*")):
        if path.suffix.lower() != ".moflex":
            continue
        match = NAME.search(path.stem)
        if not match:
            continue
        name = "Movie" + match.group(1).upper()
        if args.only and name.lower() not in {n.lower() for n in args.only}:
            continue
        movies.setdefault(name, Movie(name, str(path), 0.0))
    if not movies:
        sys.exit(f"no MovieOP.moflex ... Movie13.moflex under {args.moflex}")
    movies = dict(sorted(movies.items(), key=lambda item: (item[0] != "MovieOP", item[0])))
    print(f"{len(movies)} game movies: {' '.join(movies)}")

    # the rip
    info = probe(args.ffprobe, args.rip)
    videos = [s for s in info["streams"] if s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic")]
    audios = [s for s in info["streams"] if s.get("codec_type") == "audio"]
    if not videos or len(audios) <= args.rip_audio:
        sys.exit("the rip needs a video stream and an audio stream")
    video, audio = videos[0], audios[args.rip_audio]
    # r_frame_rate keeps 30000/1001 exact where avg_frame_rate can be rounded to 2997/100
    num, den = (int(x) for x in video.get("r_frame_rate", "0/1").split("/"))
    anum, aden = (int(x) for x in video.get("avg_frame_rate", "0/1").split("/"))
    if not num or not den or (anum and aden and abs(num / den - anum / aden) > 0.5):
        num, den = anum, aden
    fps = num / den
    order = video.get("field_order", "progressive")
    interlaced = args.deinterlace == "on" or (args.deinterlace == "auto" and order not in ("progressive", "unknown"))
    print(f"rip: {video.get('width')}x{video.get('height')} {video.get('codec_name')} {fps:.3f} fps "
          f"{'interlaced (' + order + '), deinterlacing' if interlaced else order}; "
          f"{float(info['format'].get('duration', 0)) / 60:.1f} min")
    if fps > 50 and not interlaced:
        print("  note: 50+ fps progressive; frames are kept at the game's 30 fps pace")
    shift = float(audio.get("start_time", 0) or 0) - float(info["format"].get("start_time", 0) or 0)

    print("reading the rip's sound…")
    sound = pcm(args.ffmpeg, args.rip, f"0:{audio['index']}")
    print("reading the game movies…")
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        lengths = {m.name: pool.submit(movie_seconds, args.ffprobe, m.path) for m in movies.values()}
        tracks = {m.name: [pool.submit(pcm, args.ffmpeg, m.path, f"0:a:{t}?") for t in (0, 1)] for m in movies.values()}
        for m in movies.values():
            m.seconds, m.frame_rate = lengths[m.name].result()
        tracks = {name: [f.result() for f in fs] for name, fs in tracks.items()}

    finder = Finder(sound, int(PROBE * RATE))
    start_time = float(info["format"].get("start_time", 0) or 0)
    print("finding the movies in the rip by sound, then by picture…")
    for m in movies.values():
        locate(m, tracks[m.name], finder)
        m.start += shift
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda m: refine(args, m, interlaced, video["index"], start_time), movies.values()))
    print(f"\n{'movie':8}  {'length':>8}  {'starts in rip':>14}  {'pace':>8}  probes  sound  picture moved")
    for m in movies.values():
        print(f"{m.name:8}  {m.seconds:8.2f}  {clock(m.start):>14}  {m.ratio:8.5f}  {m.agreed}/{PROBES}     {m.score:.2f}   {m.nudge * 1000:+6.1f} ms{('  <- ' + m.note) if m.note else ''}")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "alignment.json").write_text(json.dumps({m.name: asdict(m) for m in movies.values()}, indent=2))
    if args.dry_run:
        return
    print(f"\nencoding to {out} ({args.codec}, crf {args.crf})…")
    index = video["index"]

    def one(m: Movie) -> str:
        encode(args, m, interlaced, fps, index, out / f"{m.name}.mp4")
        return m.name

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        for done in concurrent.futures.as_completed([pool.submit(one, m) for m in movies.values()]):
            print(f"  {done.result()}.mp4")
    print(f"\ndone: copy {out}/Movie*.mp4 to the handheld and import them in ExaltDS (sidebar, Mods / textures, Import HD cutscenes).")


if __name__ == "__main__":
    main()
