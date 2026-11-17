# HD cutscenes

ExaltDS can play Fire Emblem Awakening's anime cutscenes in HD, from your own copy of the
**Fire Emblem HD Movie Collection** Blu-ray (it came with the Japanese *Fire Emblem Echoes*
limited edition). The game's own movies are 400x240; the disc has the same 14 movies in HD
and in full 16:9, the width the 3DS cut off.

The game keeps playing its own movie underneath. ExaltDS shows the HD copy over the top screen,
muted, frame for frame with the game's: the sound (English or Japanese), skipping with Start and
the Theater all stay the game's. A movie with no HD copy plays as it always did. Nothing of the
game or the disc comes with ExaltDS.

## Making the files (PC)

You need Python 3.9+ with numpy (`pip install numpy`) and FFmpeg 4.4 or newer.

1. **Rip the disc** with MakeMKV. The Awakening "Play All" title is enough, but any title holding
   the Awakening movies works, a whole-disc one too.
2. **Get the game's movies**, `MovieOP.moflex` and `Movie01.moflex` to `Movie13.moflex`, from your
   decrypted game's RomFS (Azahar: right-click the game, *Dump RomFS*; or 3dstool).
3. **Run the tool**:

   ```
   python build_hd_movies.py "C:\rips\title_t00.mkv" "C:\dumps\romfs" -o hd_movies
   ```

   It finds each movie in the rip by its sound, lines it up by its pictures, retimes it to the
   game's pace and writes `hd_movies\MovieOP.mp4` … `Movie13.mp4`. It prints where it found each
   one and how well it matched; a line marked `<- check` is worth a look.

Options: `--codec h264` (default `hevc`, smaller), `--crf` for quality, `--height 1080` to scale,
`--only MovieOP Movie04`, `--dry-run` to only find and report, `--with-audio` to keep the game's
sound in the files so you can check them on a PC.

## Putting them on the handheld

Copy the `.mp4` files to the device, then in ExaltDS open the sidebar (Back in game),
**Mods / textures → Import HD cutscenes…**, and choose the files (or a ZIP holding them). They play
from the next cutscene, no restart. The sidebar's **HD cutscenes** switch turns them off and on.

## How it lines up

- The game's movies and the disc's are the same edits. The disc runs at 30000/1001 fps where the
  game runs at 30, so it is 0.1 % slow; the files are sped up by exactly that.
- The opening's file says 59.831 fps (the 3DS's refresh rate), but its frames are 1/60 s apart like
  the others'. The tool goes by 60.
- On the device, ExaltDS's core notes which `.moflex` the game reads from its RomFS and times the
  movie by the console's own clock from its first frame (`crates/zakuro-core/src/movies.rs`), so the
  HD copy keeps pace with the game when emulation runs slow, fast or stops for the sidebar.
- A rip's sound can sit a frame or two off its pictures (re-encodes often shift it), so after
  finding a movie by sound the tool settles its start by comparing the rip's frames, cut to 5:3,
  with the game's own.
