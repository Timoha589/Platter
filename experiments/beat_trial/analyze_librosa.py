"""librosa's beat tracker over the decoded tracks -> librosa.json

    venv/Scripts/python analyze_librosa.py
"""
import json
import time
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

HERE = Path(__file__).parent
tracks = json.loads((HERE / "tracks.json").read_text(encoding="utf-8"))
out = {}
for track in tracks:
    y, sr = sf.read(HERE / "wav" / f"{track['id']}_22k.wav", dtype="float32")
    started = time.time()
    tempo, beats = librosa.beat.beat_track(y=y, sr=sr, units="time")
    out[track["id"]] = {
        "bpm": float(np.atleast_1d(tempo)[0]),
        "beats": [round(float(b), 4) for b in beats],
        "seconds": round(time.time() - started, 2),
    }
    print(track["id"], round(out[track["id"]]["bpm"], 1), f"{out[track['id']]['seconds']}s", flush=True)
(HERE / "librosa.json").write_text(json.dumps(out), encoding="utf-8")
