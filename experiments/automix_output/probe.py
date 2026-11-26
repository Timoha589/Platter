"""Generate a known-grid pair, record Platter's Windows audio output, and measure beat alignment."""

import argparse
import json
import subprocess
import time
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SAMPLE_RATE = 48_000
OUTGOING_HZ, INCOMING_HZ = 1_800, 3_900
OUTGOING_MARKER_HZ, INCOMING_MARKER_HZ = 2_850, 5_100
OUTGOING_MARKER_MS, INCOMING_MARKER_MS = 31_000, 3_840
INCOMING_CUE_MS = 1_920


def add_tone(samples, start_ms, length_ms, frequency, amplitude, decay_ms):
    offset = round(start_ms * SAMPLE_RATE / 1_000)
    size = min(len(samples) - offset, round(length_ms * SAMPLE_RATE / 1_000))
    t = np.arange(size) / SAMPLE_RATE
    envelope = np.minimum(1.0, t / .001) * np.exp(-t / (decay_ms / 1_000))
    samples[offset:offset + size] += amplitude * np.sin(2 * np.pi * frequency * t) * envelope


def generate(directory, terminal_bass=False):
    directory.mkdir(parents=True, exist_ok=True)
    fixture = {"positionMs": 30_000}
    for deck, duration, period, click_hz, start, end, label in [
        ("outgoing", 52_000, 500, OUTGOING_HZ, 36_000, 52_000, "outro"),
        ("incoming", 50_000, 480, INCOMING_HZ, 1_920, 17_280, "intro"),
    ]:
        beats = list(range(0, duration + 1, period))
        sections = [{"startMs": start, "endMs": end, "label": label}]
        samples = np.zeros(duration * SAMPLE_RATE // 1_000)
        for beat in beats:
            if beat == duration:
                continue
            add_tone(samples, beat, 25, click_hz, .32, 8)
            if not terminal_bass and (deck == "outgoing" or beat >= 11_520):
                add_tone(samples, beat, 170, 57 if deck == "outgoing" else 81, .42, 70)
        if terminal_bass:
            bass_start = 0 if deck == "outgoing" else end * SAMPLE_RATE // 1_000
            t = np.arange(len(samples) - bass_start) / SAMPLE_RATE
            bed = .24 * np.sin(2 * np.pi * (57 if deck == "outgoing" else 81) * t)
            samples[bass_start:] += bed * np.minimum(1.0, t / .005)
        if deck == "outgoing":
            add_tone(samples, OUTGOING_MARKER_MS, 25, OUTGOING_MARKER_HZ, .32, 8)
        else:
            add_tone(samples, INCOMING_MARKER_MS, 25, INCOMING_MARKER_HZ, .32, 8)
            sections.append({"startMs": end, "endMs": duration, "label": "verse"})
        fixture[deck] = {"durationMs": duration, "beatsMs": beats, "downbeatsMs": beats[::4], "sections": sections}
        if terminal_bass and deck == "outgoing":
            fixture[deck]["beatOffsetsMs"] = [0] * len(beats)
        if deck == "incoming":
            # This controlled onset is also checked separately using the server's actual signal analysis.
            fixture[deck]["bassEntries"] = {"version": 2 if terminal_bass else 1,
                                             "downbeatsMs": [end if terminal_bass else 11_520]}
        with wave.open(str(directory / f"{deck}.wav"), "wb") as wav:
            wav.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
            wav.writeframes((samples.clip(-1, 1) * 32767).astype("<i2").tobytes())
    (directory / "fixture.json").write_text(json.dumps(fixture), encoding="utf-8")


def wait_for(path, child, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if path.is_file() and path.stat().st_size > 0:
            return
        if child.poll() is not None:
            break
        time.sleep(.05)
    raise RuntimeError(f"Did not get {path}. See {path.parent / 'gradle.log'}")


def capture(directory, variant, baseline=False):
    from process_audio_capture import ProcessAudioCapture

    run = directory / variant
    run.mkdir()
    command = ["cmd.exe", "/d", "/c", "gradlew.bat", "-p", "desktop", "autoMixOutputProbe", "--offline",
               f"-PprobeDirectory={run}", f"-PplatterBassMidpoint={str(variant == 'A').lower()}",
               f"-PprobeCorrectPhase={str(not baseline).lower()}"]
    with (run / "gradle.log").open("w", encoding="utf-8") as log:
        child = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            pid_file = run / "player.pid"
            wait_for(pid_file, child, 180)
            pid = int(pid_file.read_text(encoding="utf-8"))
            with ProcessAudioCapture(pid, str(run / "loopback.wav")) as recorder:
                recorder.start()
                (run / "start").touch()
                wait_for(run / "finished", child, 55)
            if child.wait(timeout=20) != 0:
                raise RuntimeError(f"Playback failed. See {run / 'gradle.log'}")
            actual = (run / "planned-overlap").read_text(encoding="utf-8").strip()
            if actual != "36000 52000":
                raise RuntimeError(f"Unexpected planned overlap: {actual}. See {run / 'platter.log'}")
        finally:
            if child.poll() is None:
                child.terminate()


def detect_clicks(samples, frequency, relative_floor=.08):
    # Complex demodulation measures the energy in a narrow band irrespective of phase.
    carrier = np.exp(-2j * np.pi * frequency * np.arange(len(samples)) / SAMPLE_RATE)
    integral = np.insert(np.cumsum(samples * carrier), 0, 0)
    width = SAMPLE_RATE // 100
    energy = np.abs(integral[width:] - integral[:-width]) / width
    peaks = []
    # Keep one local maximum per 100ms. There are over 450ms between clicks of either deck.
    for index in np.flatnonzero(energy > max(.0007, float(energy.max()) * relative_floor)):
        if peaks and index - peaks[-1] < SAMPLE_RATE // 10:
            if energy[index] > energy[peaks[-1]]:
                peaks[-1] = index
        else:
            peaks.append(index)
    return (np.asarray(peaks) + width / 2) / SAMPLE_RATE * 1_000


def index_beats(clicks, markers, marker_ms, media_period, playback_period, deck):
    if len(markers) != 1 or not len(clicks):
        raise ValueError(f"Expected one {deck} marker and at least one beat, got {len(markers)} and {len(clicks)}")
    reference = int(np.argmin(abs(clicks - markers[0])))
    if abs(clicks[reference] - markers[0]) > 30:
        raise ValueError(f"{deck} reference marker does not align with its grid")
    indexed = {marker_ms: float(clicks[reference])}
    for direction in (-1, 1):
        beat, last = marker_ms, clicks[reference]
        stop = -1 if direction == -1 else len(clicks)
        for i in range(reference + direction, stop, direction):
            span = abs(clicks[i] - last) / playback_period
            intervals = round(span)
            if intervals < 1 or abs(span - intervals) > .33:
                continue
            beat += direction * intervals * media_period
            last = clicks[i]
            indexed[beat] = float(last)
    return indexed


def bass_power(samples, start_ms, stop_ms):
    start, stop = (round(ms * SAMPLE_RATE / 1_000) for ms in (start_ms, stop_ms))
    window = np.hanning(stop - start)
    bins = np.fft.rfftfreq(len(window), 1 / SAMPLE_RATE)
    power = np.abs(np.fft.rfft(samples[start:stop] * window)) ** 2
    return float(power[(bins >= 40) & (bins <= 110)].sum() / (np.sum(window ** 2) * len(window)))


def analyze(recording, launch_ms=36_000, end_ms=52_000):
    if end_ms - launch_ms < 2_000 or any(ms % 500 for ms in (launch_ms, end_ms)):
        raise ValueError("The measurement window needs at least four outgoing beats on the 500ms grid")
    decoded = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(recording), "-map", "0:a:0",
         "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "-"],
        capture_output=True, check=True,
    ).stdout
    samples = np.frombuffer(decoded, dtype="<f4")
    if len(samples) < SAMPLE_RATE:
        raise ValueError("Recording contains less than one second of output")
    outgoing = index_beats(detect_clicks(samples, OUTGOING_HZ),
                           detect_clicks(samples, OUTGOING_MARKER_HZ, .35), OUTGOING_MARKER_MS, 500, 500, "outgoing")
    incoming = index_beats(detect_clicks(samples, INCOMING_HZ),
                           detect_clicks(samples, INCOMING_MARKER_HZ, .35), INCOMING_MARKER_MS, 480, 500, "incoming")
    # Exclude two beats at each fade edge where a deck is deliberately quiet.
    expected = range(launch_ms + 1_000, end_ms - 999, 500)
    pairs = []
    miss = []
    for beat in expected:
        incoming_beat = INCOMING_CUE_MS + (beat - launch_ms) // 500 * 480
        out_time, in_time = outgoing.get(beat), incoming.get(incoming_beat)
        if out_time is None or in_time is None:
            missing = [deck for deck, detected in [("outgoing", out_time), ("incoming", in_time)] if detected is None]
            miss.append({"outgoing_media_ms": beat, "incoming_media_ms": incoming_beat, "missing": missing})
            continue
        pairs.append({"outgoing_media_ms": beat, "incoming_media_ms": incoming_beat,
                      "outgoing_capture_ms": out_time, "incoming_capture_ms": in_time, "offset_ms": in_time - out_time})
    offsets = [pair["offset_ms"] for pair in pairs]
    times = [(pair["outgoing_capture_ms"] - pairs[0]["outgoing_capture_ms"]) / 1_000 for pair in pairs]
    drift = float(np.polyfit(times, offsets, 1)[0]) if len(times) >= 2 else None
    p95 = float(np.percentile(np.abs(offsets), 95)) if offsets else None
    coverage = len(pairs) / len(expected)
    solo = 0.0
    if {32_000, 32_500, 33_000} <= outgoing.keys():
        solo = sum(bass_power(samples, outgoing[beat], outgoing[beat + 500]) for beat in (32_000, 32_500)) / 2
    bass = []
    for left in range(launch_ms + 500, end_ms - 500, 500):
        if solo > 0 and left in outgoing and left + 500 in outgoing:
            power = bass_power(samples, outgoing[left], outgoing[left + 500])
            if power > 0:
                bass.append({"outgoing_media_ms": [left, left + 500],
                             "db_relative_to_outgoing_solo": float(10 * np.log10(power / solo))})
    terminal_bass = []
    terminal = incoming.get(INCOMING_CUE_MS + (end_ms - launch_ms) // 500 * 480)
    if terminal is not None and solo > 0:
        for left in range(-600, 400, 100):
            power = bass_power(samples, terminal + left, terminal + left + 100)
            start, stop = (round((terminal + offset) * SAMPLE_RATE / 1_000) for offset in (left, left + 100))
            peak = float(np.max(np.abs(samples[start:stop]))) if 0 <= start < stop <= len(samples) else None
            terminal_bass.append({"relative_to_terminal_beat_ms": [left, left + 100],
                                  "db_relative_to_outgoing_solo": float(10 * np.log10(power / solo)) if power > 0 else None,
                                  "peak_amplitude": peak})
    return {"recording": str(recording), "window_outgoing_ms": [launch_ms, end_ms],
            "expected_pairs": len(expected), "detected_pairs": len(pairs), "coverage": coverage, "misses": miss,
            "median_signed_offset_ms": float(np.median(offsets)) if offsets else None,
            "p95_absolute_offset_ms": p95, "drift_ms_per_second": drift,
            "passes": coverage >= .95 and p95 is not None and p95 <= 20.0, "beats": pairs,
            "bass_40_to_110_hz": bass, "terminal_bass_40_to_110_hz": terminal_bass}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("generate", "capture"):
        command = commands.add_parser(name)
        command.add_argument("directory", type=Path)
        if name == "generate":
            command.add_argument("--terminal-bass", action="store_true", help="Start incoming bass at the final downbeat")
        else:
            command.add_argument("variant", choices=["A", "B"])
            command.add_argument("--baseline", action="store_true", help="Disable the Windows preroll phase correction")
    command = commands.add_parser("analyze")
    command.add_argument("wave", type=Path)
    command.add_argument("--launch-ms", type=int, default=36_000)
    command.add_argument("--end-ms", type=int, default=52_000)
    args = parser.parse_args()
    if args.command == "generate":
        generate(args.directory.resolve(), args.terminal_bass)
    elif args.command == "capture":
        capture(args.directory.resolve(), args.variant, args.baseline)
    else:
        print(json.dumps(analyze(args.wave, args.launch_ms, args.end_ms), indent=2))


if __name__ == "__main__":
    main()
