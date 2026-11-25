"""Measure the loudness of transition windows and quiet edges with FFmpeg."""

import re
import subprocess


STEP_MS = 500
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


def beat_offsets(path, beats):
    """Measure the attack near each grid marker, leaving weak or ambiguous beats unmarked."""
    import numpy as np

    samples_per_ms = 4
    decoded = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-map", "0:a:0", "-ac", "1",
         "-af", "highpass=f=45,lowpass=f=150", "-ar", "4000", "-f", "f32le", "-"],
        capture_output=True, check=True, timeout=30,
    ).stdout
    samples = np.frombuffer(decoded, dtype="<f4")
    power = np.concatenate(([0.0], np.cumsum(samples * samples, dtype=np.float64)))
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
