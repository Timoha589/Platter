"""Export the bundled AutoMix beat grids. Run with Python 3."""

import json
from bisect import bisect_left
from pathlib import Path
from statistics import median


HERE = Path(__file__).parent
DESTINATION = HERE.parents[1] / "automix-common/src/main/resources/automix-trial.json"
BEAT_TOLERANCE = 0.070


def read(name):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def rhythm(beats):
    gaps = [end - start for start, end in zip(beats, beats[1:])]
    if len(gaps) < 8:
        return 0, 0
    period = median(gaps)
    return period, sum(abs(gap / period - 1) < 0.05 for gap in gaps) / len(gaps)


def agreed_beats(beats, reference):
    matches = []
    for beat in beats:
        i = bisect_left(reference, beat)
        if any(abs(other - beat) <= BEAT_TOLERANCE for other in reference[max(0, i - 1):i + 1]):
            matches.append(beat)
    return matches


def outro_beats(agreed, lib, ess, duration):
    tail = [beat for beat in agreed if beat >= duration - 14]
    # librosa trims weak beats at the end. Continue with Essentia only if it
    # tracked the preceding beats too. The player still checks the whole run.
    if len(tail) >= 5 and tail[-1] == lib[-1] and duration - tail[-1] <= 4:
        offset = median(beat - min(ess, key=lambda t: abs(beat - t)) for beat in tail[-5:])
        last = tail[-1]
        tail.extend(t + offset for t in ess if last + 0.2 < t + offset < duration)
    return [round(t * 1000) for t in tail if t < duration]


def export():
    tracks = read("tracks.json")
    essentia, librosa = read("essentia.json"), read("librosa.json")
    catalog = []
    for track in tracks:
        key, duration = track["id"], track["duration"]
        ess, lib = essentia[key]["beats"], librosa[key]["beats"]
        ess_period, steady = rhythm(ess)
        lib_period, _ = rhythm(lib)
        if not ess_period or not lib_period or abs(lib_period / ess_period - 1) >= 0.03 or steady < 0.85:
            continue

        agreed = agreed_beats(lib, ess)
        precision, recall = len(agreed) / len(lib), len(agreed_beats(ess, lib)) / len(ess)
        if not precision or 2 * precision * recall / (precision + recall) < 0.8:
            continue

        catalog.append({
            "id": key,
            "title": track["title"],
            "artist": track["artist"],
            "durationMs": round(duration * 1000),
            "intro": [round(t * 1000) for t in agreed if t <= 14],
            "outro": outro_beats(agreed, lib, ess, duration),
        })

    rows = ",\n".join(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) for entry in catalog)
    DESTINATION.write_text(f"[\n{rows}\n]\n", encoding="utf-8", newline="\n")
    print(f"Exported {len(catalog)} of {len(tracks)} tracks")


if __name__ == "__main__":
    export()
