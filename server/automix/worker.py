"""Analyze Navidrome tracks and serve cached timing metadata to Platter."""

import argparse
import hashlib
import json
import logging
import os
import random
import sqlite3
import subprocess
import threading
import time
from collections import OrderedDict
from contextlib import closing
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from audio_levels import BASS_ENTRY_VERSION, beat_features, measure
from audio_muse import AudioMuse
from journal import AUTH_KEYS, Navidrome


ENDPOINT = "/rest/platterAutoMix"
MAX_IDS = 8
RETRY_SECONDS = 3600


class Catalog:
    def __init__(self, db):
        self.db = db.resolve()

    def tracks(self, ids=None):
        fields = "SELECT m.id, m.library_id, m.path, m.duration, m.updated_at, m.size, l.path "
        sql = fields + "FROM media_file m JOIN library l ON l.id=m.library_id WHERE m.missing=0 AND m.duration>0"
        if ids is not None:
            sql += " AND m.id IN (" + ",".join("?" for _ in ids) + ")"
        with closing(sqlite3.connect(self.db.as_uri() + "?mode=ro", uri=True, timeout=10)) as db:
            return {row[0]: row[1:] for row in db.execute(sql, ids or ())}

    @staticmethod
    def audio(track):
        _, path, _, _, _, library = track
        root = Path(library).resolve(strict=True)
        source = (root / path).resolve(strict=True)
        if not source.is_relative_to(root) or not source.is_file():
            raise ValueError("Track is outside its mounted music library")
        return source


class Cache:
    def __init__(self, db, model):
        self.db = db.resolve()
        self.model = model
        with closing(sqlite3.connect(self.db)) as conn, conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("CREATE TABLE IF NOT EXISTS structure (id TEXT PRIMARY KEY, stamp TEXT NOT NULL, data TEXT, retry INTEGER)")

    def stamp(self, track):
        return hashlib.sha256(repr((1, self.model, track)).encode()).hexdigest()

    def lookup(self, track_id, track):
        with closing(sqlite3.connect(self.db)) as db:
            row = db.execute("SELECT data,retry FROM structure WHERE id=? AND stamp=?", (track_id, self.stamp(track))).fetchone()
        if row and row[0] is not None:
            return "ready", json.loads(row[0])
        return ("failed" if row and row[1] > time.time() else "pending"), None

    def missing(self, tracks):
        with closing(sqlite3.connect(self.db)) as db:
            known = dict(db.execute("SELECT id, stamp FROM structure WHERE data IS NOT NULL OR retry>?", (time.time(),)))
        return (track_id for track_id, track in tracks.items() if known.get(track_id) != self.stamp(track))

    def save(self, track_id, track, data):
        retry = None if data is not None else int(time.time() + RETRY_SECONDS)
        body = None if data is None else json.dumps(data, separators=(",", ":"), allow_nan=False)
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("INSERT OR REPLACE INTO structure VALUES (?,?,?,?)", (track_id, self.stamp(track), body, retry))


def timing(track, result):
    duration = round(track[2] * 1000)

    def milliseconds(seconds):
        return min(duration, max(0, round(seconds * 1000)))

    segments = []
    for part in result.segments:
        start, end = milliseconds(part.start), milliseconds(part.end)
        if start < end:
            segments.append({"startMs": start, "endMs": end, "label": part.label})
    return {"durationMs": duration, "beatsMs": sorted({milliseconds(t) for t in result.beats}),
            "downbeatsMs": sorted({milliseconds(t) for t in result.downbeats}), "sections": segments}


class Work:
    def __init__(self, catalog, cache, audio_muse=None, measure_levels=None, measure_beats=None):
        self.catalog = catalog
        self.cache = cache
        self.audio_muse = audio_muse
        self.measure_levels = measure_levels
        self.measure_beats = measure_beats
        self.lock = threading.Condition()
        self.priority = OrderedDict()
        self.active = None

    def needs_levels(self, result):
        return (self.measure_levels is not None and "audioLevels" not in result
                and result.get("levelsRetryAt", 0) <= time.time())

    def needs_beat_features(self, result):
        return (self.measure_beats is not None and result.get("beatsMs")
                and ("beatOffsetsMs" not in result or (result.get("bassEntries") or {}).get("version") != BASS_ENTRY_VERSION)
                and result.get("beatOffsetsRetryAt", 0) <= time.time())

    def request(self, ids, libraries):
        tracks = self.catalog.tracks(ids)
        ready, pending = {}, []
        for track_id in ids:
            track = tracks.get(track_id)
            if track is None or track[0] not in libraries:
                continue
            status, result = self.cache.lookup(track_id, track)
            if status == "ready":
                if self.needs_levels(result) or self.needs_beat_features(result):
                    pending.append(track_id)
                result.pop("levelsRetryAt", None)
                result.pop("beatOffsetsRetryAt", None)
                ready[track_id] = result
            elif status == "pending":
                pending.append(track_id)
        if pending:
            with self.lock:
                for track_id in reversed(pending):
                    if track_id != self.active:
                        self.priority[track_id] = None
                        self.priority.move_to_end(track_id, last=False)
                while len(self.priority) > 256:
                    self.priority.popitem()
                self.lock.notify()
        if ready and self.audio_muse is not None:
            for track_id, profile in self.audio_muse.get(ready).items():
                ready[track_id]["audioMuse"] = profile
        return {"tracks": ready, "pending": pending}

    def run(self, bulk, infer):
        remaining = iter(())
        rescan_at = 0
        while True:
            with self.lock:
                if self.priority:
                    self.active = self.priority.popitem(last=False)[0]
                else:
                    self.active = next(remaining, None)
            if self.active is None and bulk and time.monotonic() >= rescan_at:
                remaining = iter(self.cache.missing(self.catalog.tracks()))
                rescan_at = time.monotonic() + 300
                continue
            if self.active is None:
                with self.lock:
                    self.lock.wait_for(lambda: bool(self.priority), timeout=5)
                continue
            self.process(self.active, infer)
            with self.lock:
                self.active = None

    def process(self, track_id, infer):
        track = self.catalog.tracks([track_id]).get(track_id)
        if track is None:
            return
        status, result = self.cache.lookup(track_id, track)
        if status == "pending":
            encode(self.catalog, self.cache, track_id, track, infer)
            status, result = self.cache.lookup(track_id, track)
        if status != "ready":
            return
        if self.needs_levels(result):
            try:
                result["audioLevels"] = self.measure_levels(self.catalog.audio(track), result["durationMs"])
                result.pop("levelsRetryAt", None)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                logging.warning("Audio levels unavailable for %s (%s)", track_id, type(error).__name__)
                result["levelsRetryAt"] = time.time() + RETRY_SECONDS
            self.cache.save(track_id, track, result)
        if self.needs_beat_features(result):
            try:
                offsets, bass_entries = self.measure_beats(
                    self.catalog.audio(track), result["beatsMs"], result.get("downbeatsMs", []))
                result["beatOffsetsMs"] = offsets
                result["bassEntries"] = bass_entries
                result.pop("beatOffsetsRetryAt", None)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                logging.warning("Beat features unavailable for %s (%s)", track_id, type(error).__name__)
                result["beatOffsetsRetryAt"] = time.time() + RETRY_SECONDS
            self.cache.save(track_id, track, result)


def encode(catalog, cache, track_id, track, infer):
    started = time.monotonic()
    data = None
    try:
        data = timing(track, infer(catalog.audio(track)))
    except (ValueError, OSError, RuntimeError) as error:
        logging.warning("%s unavailable: %s", track_id, str(error).splitlines()[0][:240] if str(error) else type(error).__name__)
    if cache is not None:
        cache.save(track_id, track, data)
    if data is not None:
        logging.info("%s ready in %.1f s", track_id, time.monotonic() - started)
    return data is not None


class Handler(BaseHTTPRequestHandler):
    def __init__(self, *args, work, navidrome, endpoint, **kwargs):
        self.work = work
        self.navidrome = navidrome
        self.endpoint = endpoint
        super().__init__(*args, **kwargs)

    def log_message(self, *_args):
        pass  # The URL carries Subsonic credentials.

    def reply(self, body, status=200):
        data = json.dumps({"subsonic-response": body}, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parts = urlsplit(self.path)
        if parts.path != self.endpoint:
            self.send_error(404)
            return
        try:
            query = parse_qs(parts.query, max_num_fields=20)
            ids = list(dict.fromkeys(query.get("id", ())))
            if not 0 < len(ids) <= MAX_IDS or any(len(track_id) > 128 for track_id in ids):
                self.send_error(400)
                return
            auth = {key: query[key][0] for key in AUTH_KEYS if key in query}
            if not auth.get("u") or not (auth.get("p") or auth.get("t") and auth.get("s")):
                self.reply({"status": "failed", "error": {"code": 10, "message": "Subsonic credentials required"}})
                return
            user = self.navidrome.request("getMusicFolders", auth)
            if user["status"] != "ok":
                self.reply(user)
                return
            libraries = {int(folder["id"]) for folder in user["musicFolders"].get("musicFolder", [])}
            result = self.work.request(ids, libraries)
            self.reply({"status": "ok", "version": "1.16.1", "platterAutoMix": result})
        except (OSError, sqlite3.Error, ValueError, KeyError, TypeError) as error:
            logging.warning("AutoMix request failed (%s)", type(error).__name__)
            self.reply({"status": "failed", "error": {"code": 0, "message": "AutoMix temporarily unavailable"}}, 503)


def benchmark(catalog, limit, infer):
    sample = list(catalog.tracks().items())
    if len(sample) < 2:
        raise SystemExit("At least two tracks are needed for a warmed benchmark")
    random.Random(42).shuffle(sample)
    warm_id, warm_track = sample[0]
    if not encode(catalog, None, warm_id, warm_track, infer):
        raise SystemExit("Warm-up failed. Check the music mounts and model output.")
    tracks = sample[1:limit + 1]
    started = time.monotonic()
    passed = sum(encode(catalog, None, track_id, track, infer) for track_id, track in tracks)
    elapsed = time.monotonic() - started
    if not passed:
        raise SystemExit("No sampled tracks could be analyzed")
    seconds = elapsed / len(tracks)
    print(f"{passed}/{len(tracks)} analyzed in {elapsed:.1f} s, {seconds:.1f} s/track after warm-up; "
          f"estimated 90,000 tracks: {seconds * 90_000 / 86_400:.1f} days running nonstop", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("serve", "benchmark"), default="serve", nargs="?")
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    if args.limit < 1:
        parser.error("--limit must be positive")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    catalog = Catalog(Path("/navidrome/navidrome.db"))
    if not catalog.db.is_file():
        parser.error("Mount the Navidrome data directory containing navidrome.db")
    model = os.getenv("MODEL", "harmonix-all")
    device = os.getenv("ANALYSIS_DEVICE", "cuda")
    from allin1_infer import AllInOneSession
    with AllInOneSession(model=model, device=device) as session:
        if args.mode == "benchmark":
            benchmark(catalog, args.limit, session.infer)
            return
        try:
            muse = AudioMuse.configured()
        except ValueError:
            logging.warning("Invalid AUDIOMUSE_URL; using local analysis")
            muse = None
        work = Work(catalog, Cache(Path("/state/automix.db"), model), muse, measure, beat_features)
        navidrome_url = os.environ["NAVIDROME_URL"]
        navidrome = Navidrome(navidrome_url)
        endpoint = urlsplit(navidrome_url).path.rstrip("/") + ENDPOINT
        handler = partial(Handler, work=work, navidrome=navidrome, endpoint=endpoint)
        with ThreadingHTTPServer(("0.0.0.0", 4535), handler) as server:
            logging.info("AutoMix is ready on port 4535 (model=%s, device=%s)", model, device)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            work.run(os.getenv("SCAN_LIBRARY") == "1", session.infer)


if __name__ == "__main__":
    main()
