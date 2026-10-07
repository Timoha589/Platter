"""Compares the two analysers and the files' own BPM tags; writes report.md and
click-track previews (clicks/NN_essentia.mp3, NN_librosa.mp3) to check by ear.

    venv/Scripts/python report.py
"""
import json
import subprocess
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

HERE = Path(__file__).parent
tracks = json.loads((HERE / "tracks.json").read_text(encoding="utf-8"))
lib = json.loads((HERE / "librosa.json").read_text())
ess = json.loads((HERE / "essentia.json").read_text())

TOL = 0.03  # a tempo within 3% counts as the same


def relation(a, b):
    """How tempo a sits to tempo b: the same, an octave off, or something else."""
    if not a or not b:
        return "-"
    for label, factor in (("same", 1), ("x2", 2), ("half", 0.5), ("3:2", 1.5), ("2:3", 2 / 3)):
        if abs(a / b / factor - 1) < TOL:
            return label
    return "other"


def interval_stats(beats):
    gaps = np.diff(beats)
    if len(gaps) < 8:
        return 0.0, 0.0
    median = float(np.median(gaps))
    steady = float(np.mean(np.abs(gaps / median - 1) < 0.05))
    return 60 / median, steady


def f_measure(a, b, tolerance=0.07):
    """Share of beats the two trackers put in the same place, within 70 ms."""
    a, b = np.asarray(a), np.asarray(b)
    if not len(a) or not len(b):
        return 0.0
    hits_a = sum(np.min(np.abs(b - t)) <= tolerance for t in a)
    hits_b = sum(np.min(np.abs(a - t)) <= tolerance for t in b)
    precision, recall = hits_a / len(a), hits_b / len(b)
    return 0.0 if hits_a == 0 else 2 * precision * recall / (precision + recall)


def grid_fit(onsets, sr, beats, hop=256):
    """How much stronger the sound's attacks are on the beats than anywhere else (1 = no better than chance)."""
    frames = librosa.time_to_frames(beats, sr=sr, hop_length=hop)
    hits = [onsets[max(0, f - 2): f + 3].max() for f in frames if f < len(onsets)]
    return float(np.mean(hits) / (onsets.mean() + 1e-9)) if hits else 0.0


def downbeat(y, sr, beats):
    """Which of four beats carries the bass: (phase, confidence). Not for a track in three."""
    if len(beats) < 16:
        return 0, 0.0
    hop = 256
    spectrum = np.abs(librosa.stft(y, n_fft=2048, hop_length=hop)) ** 2
    low = spectrum[: int(150 / (sr / 2048)) + 1].sum(axis=0)
    flux = np.maximum(0, np.diff(np.log1p(low), prepend=0))
    frames = librosa.time_to_frames(beats, sr=sr, hop_length=hop)
    strength = np.array([flux[max(0, f - 3): f + 4].max() for f in frames if f < len(flux)])
    scores = np.array([strength[p::4].mean() for p in range(4)])
    order = np.argsort(scores)[::-1]
    return int(order[0]), float((scores[order[0]] - scores[order[1]]) / (scores[order[0]] + 1e-9))


def click_preview(wav, beats, phase, name, start, length=25):
    y, sr = sf.read(wav, dtype="float32")
    segment = y[int(start * sr): int((start + length) * sr)].copy()
    t = np.arange(int(0.04 * sr)) / sr
    for i, beat in enumerate(beats):
        position = int((beat - start) * sr)
        if not 0 <= position < len(segment) - len(t):
            continue
        strong = (i - phase) % 4 == 0
        pitch, gain = (700, 0.9) if strong else (1800, 0.45)
        segment[position: position + len(t)] += (gain * np.sin(2 * np.pi * pitch * t) * np.exp(-t * 90)).astype("float32")
    tmp = HERE / "clicks" / f"{name}.wav"
    sf.write(tmp, np.clip(segment, -1, 1), sr)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(tmp), "-b:a", "96k", str(tmp.with_suffix(".mp3"))], check=True)
    tmp.unlink()


(HERE / "clicks").mkdir(exist_ok=True)
rows = []
for track in tracks:
    i = track["id"]
    y, sr = sf.read(HERE / "wav" / f"{i}_22k.wav", dtype="float32")
    le, ee = lib[i], ess[i]
    lib_bpm, lib_steady = interval_stats(le["beats"])
    ess_bpm, ess_steady = interval_stats(ee["beats"])
    between = relation(ess_bpm, lib_bpm)
    phase_e, phase_conf = downbeat(y, sr, ee["beats"])
    onsets = librosa.onset.onset_strength(y=y, sr=sr, hop_length=256)
    fit_e, fit_l = grid_fit(onsets, sr, ee["beats"]), grid_fit(onsets, sr, le["beats"])
    fm = f_measure(le["beats"], ee["beats"]) if between == "same" else 0.0
    # What the grid would be trusted for: the two agree on tempo and place, and the beat is steady.
    trusted = between == "same" and fm >= 0.8 and ess_steady >= 0.85
    rows.append({
        "id": i, "name": f"{track['artist']} - {track['title']}"[:46], "tag": track["tag_bpm"],
        "ess": ess_bpm, "lib": lib_bpm, "vs_tag_ess": relation(ess_bpm, track["tag_bpm"]),
        "vs_tag_lib": relation(lib_bpm, track["tag_bpm"]), "between": between, "f": fm,
        "fit_e": fit_e, "fit_l": fit_l, "steady": ess_steady, "conf": ee["confidence"], "phase": phase_e, "dconf": phase_conf, "trusted": trusted,
        "ess_s": ee["seconds"], "lib_s": le["seconds"],
    })
    start = track["duration"] * 0.4
    click_preview(HERE / "wav" / f"{i}_22k.wav", [b for b in ee["beats"]], phase_e, f"{i}_essentia", start)
    click_preview(HERE / "wav" / f"{i}_22k.wav", [b for b in le["beats"]], 0, f"{i}_librosa", start)

tagged = [r for r in rows if r["tag"]]


def share(key, label, subset):
    return sum(r[key] == label for r in subset), len(subset)


lines = ["# Beat grid trial: 30 tracks", "",
         "| # | track | tag | essentia | librosa | ess vs tag | lib vs tag | ess vs lib | F | fit e/l | steady | conf | trusted |",
         "|---|-------|-----|----------|---------|-----------|-----------|-----------|---|---------|--------|------|---------|"]
for r in rows:
    lines.append(f"| {r['id']} | {r['name']} | {r['tag'] or ''} | {r['ess']:.1f} | {r['lib']:.1f} | {r['vs_tag_ess']} | "
                 f"{r['vs_tag_lib']} | {r['between']} | {r['f']:.2f} | {r['fit_e']:.1f}/{r['fit_l']:.1f} | {r['steady']:.2f} | {r['conf']:.1f} | {'yes' if r['trusted'] else 'no'} |")
lines += ["", "## Summary", ""]
for key, who in (("vs_tag_ess", "essentia"), ("vs_tag_lib", "librosa")):
    counts = {label: share(key, label, tagged)[0] for label in ("same", "x2", "half", "3:2", "2:3", "other")}
    lines.append(f"- {who} against the {len(tagged)} files' BPM tags: " + ", ".join(f"{k} {v}" for k, v in counts.items() if v))
lines.append(f"- essentia and librosa agree on tempo for {sum(r['between'] == 'same' for r in rows)} of {len(rows)}; "
             f"octave or other disagreement for {sum(r['between'] != 'same' for r in rows)}")
disputed = [r for r in rows if r["between"] != "same" or r["f"] < 0.8]
lines.append(f"- where they disagree or do not line up ({len(disputed)} tracks), the beats sit on the stronger attacks "
             f"for essentia in {sum(r['fit_e'] > r['fit_l'] for r in disputed)}, for librosa in {sum(r['fit_l'] > r['fit_e'] for r in disputed)}")
lines.append(f"- mean fit over all tracks: essentia {np.mean([r['fit_e'] for r in rows]):.2f}, librosa {np.mean([r['fit_l'] for r in rows]):.2f}")
lines.append(f"- trusted grid (agreement + F >= 0.8 + steady >= 0.85): {sum(r['trusted'] for r in rows)} of {len(rows)}")
lines.append(f"- downbeat (which of four beats carries the bass) clearly stands out in {sum(r['dconf'] >= 0.15 for r in rows)} of {len(rows)} tracks; "
             f"it is the strong low click in clicks/NN_essentia.mp3")
lines.append(f"- time per track: essentia {np.mean([r['ess_s'] for r in rows]):.1f} s, librosa {np.mean([r['lib_s'] for r in rows]):.2f} s (on a 16-thread PC, one track at a time)")
(HERE / "report.md").write_text("\n".join(lines), encoding="utf-8")
print("\n".join(lines))
