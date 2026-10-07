"""Picks 30 tracks from the local Deemix downloads and decodes them for the analysers.

Writes tracks.json (path, tags, tag BPM if the file has one) and, per track, mono
decodes at 22.05 kHz (librosa) and 44.1 kHz (essentia) into wav/.

    python prepare.py [--count 30] [--seed 7]
"""
import argparse
import json
import random
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

LIBRARY = Path(r"C:\Users\mrtim\Documents\My Projects\Deemix plus\downloads")
# Game-music remixes: tops the sample up with other music when the library runs out of artists.
FILLER = Path(r"C:\Users\mrtim\Documents\My Projects\Stream Helper\music")
HERE = Path(__file__).parent
MIN_S, MAX_S = 90, 420


def probe(path: Path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags", "-of", "json", str(path)],
        capture_output=True, text=True, encoding="utf-8",
    ).stdout
    try:
        info = json.loads(out)["format"]
    except (KeyError, json.JSONDecodeError):
        return None
    tags = {k.lower(): v for k, v in info.get("tags", {}).items()}
    bpm = None
    for key in ("tbpm", "bpm", "tempo"):
        if key in tags:
            try:
                bpm = float(tags[key])
            except ValueError:
                pass
            break
    return {
        "path": str(path),
        "artist": tags.get("artist", path.parent.parent.name),
        "title": tags.get("title", path.stem),
        "duration": float(info.get("duration", 0)),
        "tag_bpm": bpm if bpm and bpm > 0 else None,
    }


def decode(src: str, dst: Path, rate: int):
    if dst.exists():
        return
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", src, "-ac", "1", "-ar", str(rate), "-c:a", "pcm_s16le", str(dst)],
        check=True,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    files = sorted(p for p in LIBRARY.rglob("*.mp3"))
    print(f"{len(files)} files in the library")
    with ThreadPoolExecutor(8) as pool:
        probed = [t for t in pool.map(probe, files) if t and MIN_S <= t["duration"] <= MAX_S]
    tagged = [t for t in probed if t["tag_bpm"]]
    print(f"{len(probed)} usable, {len(tagged)} with a BPM tag")

    # At most four tracks per artist, so thirty tracks are a spread of different
    # music; most with a tag to compare against, a few without.
    random.seed(args.seed)
    random.shuffle(tagged)
    random.shuffle(probed)
    chosen, per_artist = [], {}
    for pool_, limit in ((tagged, args.count - 6), (probed, args.count)):
        for track in pool_:
            if len(chosen) >= limit:
                break
            if per_artist.get(track["artist"], 0) >= 4 or track in chosen:
                continue
            per_artist[track["artist"]] = per_artist.get(track["artist"], 0) + 1
            chosen.append(track)
    if len(chosen) < args.count:
        extra = sorted(FILLER.glob("*.mp3"))
        random.shuffle(extra)
        with ThreadPoolExecutor(8) as pool:
            filler = [t for t in pool.map(probe, extra[: args.count * 3]) if t and MIN_S <= t["duration"] <= MAX_S]
        chosen += filler[: args.count - len(chosen)]
    random.shuffle(chosen)

    (HERE / "wav").mkdir(exist_ok=True)
    for i, track in enumerate(chosen, 1):
        track["id"] = f"{i:02d}"
        decode(track["path"], HERE / "wav" / f"{track['id']}_22k.wav", 22050)
        decode(track["path"], HERE / "wav" / f"{track['id']}_44k.wav", 44100)
        print(track["id"], track["artist"], "-", track["title"], track["tag_bpm"])
    (HERE / "tracks.json").write_text(json.dumps(chosen, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
