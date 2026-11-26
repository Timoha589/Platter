"""Measure the loudness of transition windows and quiet edges with FFmpeg."""

import re
import subprocess


STEP_MS = 500
BASS_ENTRY_VERSION = 2
FIRST_SAMPLE_MS = 300  # Centre of the first complete 400 ms momentary window.
FILTER = (
    "asetpts=PTS-STARTPTS,silencedetect=n=-55dB:d=0.25,ebur128=metadata=1,"
    r"aselect=not(mod(n+1\,5)),ametadata=mode=print:key=lavfi.r128.M:file=-"
)


def measure(path, duration_ms):
    result = subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-i", str(path), "-map", "0:a:0",
         "-af", FILTER, "-f", "null", "-"],
        capture_output=True, check=True, timeout=30, text=True, encoding="utf-8", errors="replace",
    )
    loudness = [round(float(value), 1) for value in re.findall(r"lavfi\.r128\.M=(-?\d+(?:\.\d+)?)", result.stdout)]
    if not loudness:
        raise ValueError("FFmpeg returned no loudness measurements")

    first_sound, last_sound = 0, duration_ms
    quiet = re.findall(r"silence_start: (-?[\d.]+).*?silence_end: (-?[\d.]+)", result.stderr, re.DOTALL)
    for start, end in quiet:
        start_ms, end_ms = round(float(start) * 1000), round(float(end) * 1000)
        if abs(start_ms) <= 20:
            first_sound = min(duration_ms, end_ms)
        if end_ms >= duration_ms - 100:
            last_sound = max(0, start_ms)
    if first_sound >= last_sound:
        first_sound, last_sound = 0, duration_ms

    return {"startMs": FIRST_SAMPLE_MS, "stepMs": STEP_MS, "momentaryLufs": loudness,
            "firstSoundMs": first_sound, "lastSoundMs": last_sound}


def low_band_power(path):
    import numpy as np

    decoded = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-map", "0:a:0", "-ac", "1",
         "-af", "highpass=f=45,lowpass=f=150", "-ar", "4000", "-f", "f32le", "-"],
        capture_output=True, check=True, timeout=30,
    ).stdout
    samples = np.frombuffer(decoded, dtype="<f4")
    return np.concatenate(([0.0], np.cumsum(samples * samples, dtype=np.float64)))


def attack_offsets(power, beats):
    """Measure the attack near each grid marker, leaving weak or ambiguous beats unmarked."""
    import numpy as np

    samples_per_ms = 4
    window = 8 * samples_per_ms
    offsets = []
    for beat in beats:
        times = np.arange(beat - 65, beat + 86)
        centers = times * samples_per_ms
        if centers[0] < window or centers[-1] + window >= len(power):
            offsets.append(None)
            continue
        before = np.sqrt((power[centers] - power[centers - window]) / window)
        after = np.sqrt((power[centers + window] - power[centers]) / window)
        attack = after - before
        hit = int(np.argmax(attack))
        if hit == 0 or hit == len(attack) - 1 or attack[hit] < max(0.006, float(np.max(after)) * 0.2):
            offsets.append(None)
        else:
            offsets.append(int(times[hit] - beat))
    return offsets


def sustained_bass_entries(power, beats, downbeats, offsets):
    """Find downbeat attacks followed by repeated bass, with a quieter lead-in."""
    import numpy as np

    samples_per_ms = 4
    if not beats:
        return []
    starts = np.maximum(0, np.asarray(beats, dtype=np.int64) + np.asarray(
        [-25 if offset is None else offset - 10 for offset in offsets], dtype=np.int64)) * samples_per_ms
    window = 180 * samples_per_ms
    stops = np.minimum(starts + window, len(power) - 1)
    starts = np.minimum(starts, stops)
    rms = np.sqrt((power[stops] - power[starts]) / window)
    floor = max(0.006, float(np.percentile(rms, 75)) * 0.20)
    strong = []
    grid = np.asarray(beats)
    for downbeat in downbeats:
        after = int(np.searchsorted(grid, downbeat))
        neighbors = [i for i in (after - 1, after) if 0 <= i < len(beats)]
        if not neighbors:
            continue
        i = min(neighbors, key=lambda index: abs(beats[index] - downbeat))
        if abs(beats[i] - downbeat) > 45 or i < 4 or i + 3 >= len(beats):
            continue
        if any(not 240 <= beats[j] - beats[j - 1] <= 850 for j in range(i - 2, i + 4)):
            continue
        lead = float(np.median(rms[i - 4:i]))
        before = max(0, starts[i] - window)
        pre_hit = np.sqrt((power[starts[i]] - power[before]) / window)
        if rms[i] < max(floor, lead * 1.8, pre_hit * 1.5):
            continue
        # A pickup can raise one of the lead-in beats. A syncopated bass line can leave
        # one of the following beats empty or softer than the downbeat.
        following_floor = max(floor, lead * 1.45, float(rms[i]) * 0.28)
        if np.count_nonzero(rms[i + 1:i + 4] >= following_floor) >= 2:
            strong.append(downbeat)
    return strong


def beat_features(path, beats, downbeats):
    power = low_band_power(path)
    offsets = attack_offsets(power, beats)
    return offsets, {"version": BASS_ENTRY_VERSION,
                     "downbeatsMs": sustained_bass_entries(power, beats, downbeats, offsets)}
