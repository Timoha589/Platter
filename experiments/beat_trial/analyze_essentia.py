"""Essentia's multifeature rhythm extractor over the decoded tracks -> essentia.json

Essentia has no Windows wheels, so this runs in WSL:
    wsl -d Ubuntu -- python3 analyze_essentia.py
"""
import json
import time
from pathlib import Path

import essentia.standard as es
import soundfile as sf

HERE = Path(__file__).parent
tracks = json.loads((HERE / "tracks.json").read_text(encoding="utf-8"))
out = {}
for track in tracks:
    y, sr = sf.read(HERE / "wav" / f"{track['id']}_44k.wav", dtype="float32")
    started = time.time()
    bpm, ticks, confidence, _, _ = es.RhythmExtractor2013(method="multifeature")(y)
    out[track["id"]] = {
        "bpm": float(bpm),
        "beats": [round(float(t), 4) for t in ticks],
        "confidence": float(confidence),
        "seconds": round(time.time() - started, 2),
    }
    print(track["id"], round(bpm, 1), round(float(confidence), 2), f"{out[track['id']]['seconds']}s", flush=True)
(HERE / "essentia.json").write_text(json.dumps(out), encoding="utf-8")
