import json
import math
import shutil
import sqlite3
import sys
import tempfile
import threading
import unittest
import wave
from array import array
from contextlib import closing
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import call, patch
from urllib.parse import parse_qs, urlsplit
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "navidrome-journal"))
from worker import Cache, Catalog, Handler, Work, encode
from audio_muse import AudioMuse
from audio_levels import beat_features, measure


class WorkerTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        library = root / "music"
        library.mkdir()
        self.audio = library / "track.flac"
        self.audio.touch()
        self.db = root / "navidrome.db"
        with closing(sqlite3.connect(self.db)) as db, db:
            db.executescript("""
                CREATE TABLE library (id INTEGER PRIMARY KEY, path TEXT);
                CREATE TABLE media_file (id TEXT PRIMARY KEY, library_id INTEGER, path TEXT,
                    duration REAL, updated_at TEXT, size INTEGER, missing INTEGER);
            """)
            db.executemany("INSERT INTO library VALUES (?,?)", [(1, str(library)), (2, str(root))])
            db.executemany("INSERT INTO media_file VALUES (?,?,?,?,?,?,?)", [
                ("song", 1, "track.flac", 60.2, "a", 100, 0),
                ("other", 1, str(self.audio), 60.2, "a", 100, 0),
                ("private", 2, "other.flac", 180, "a", 120, 0),
            ])
        self.catalog = Catalog(self.db)
        self.cache = Cache(root / "cache.db", "test")
        self.work = Work(self.catalog, self.cache)

    def test_priority_permissions_persistence_and_invalidation(self):
        self.assertEqual(self.work.request(["other", "private"], {1}), {"tracks": {}, "pending": ["other"]})
        self.assertEqual(self.work.request(["song", "other"], {1})["pending"], ["song", "other"])
        self.assertEqual(list(self.work.priority), ["song", "other"])
        track = self.catalog.tracks(["song"])["song"]

        def infer(path):
            self.assertEqual(path, self.audio)
            return SimpleNamespace(beats=[0.4, 0.9], downbeats=[0.4],
                                   segments=[SimpleNamespace(start=0.4, end=30, label="intro")])

        self.assertTrue(encode(self.catalog, self.cache, "song", track, infer))
        result = Work(self.catalog, Cache(self.cache.db, "test")).request(["song"], {1})
        self.assertEqual(result["pending"], [])
        self.assertEqual(result["tracks"]["song"], {"durationMs": 60200, "beatsMs": [400, 900],
                         "downbeatsMs": [400], "sections": [{"startMs": 400, "endMs": 30000, "label": "intro"}]})
        self.assertEqual(self.work.request(["song"], {2})["tracks"], {})
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("UPDATE media_file SET updated_at='b' WHERE id='song'")
        self.assertEqual(self.work.request(["song"], {1})["pending"], ["song"])

    def test_confines_paths_and_remembers_failures(self):
        outside = self.audio.parent.parent / "outside.flac"
        outside.touch()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("UPDATE media_file SET path=? WHERE id='song'", ("../outside.flac",))
        track = self.catalog.tracks(["song"])["song"]
        self.assertFalse(encode(self.catalog, self.cache, "song", track, lambda _: self.fail("escaped mount")))
        self.assertEqual(self.work.request(["song"], {1}), {"tracks": {}, "pending": []})

    def test_audio_levels_backfill_keeps_ready_grid_available(self):
        track = self.catalog.tracks(["song"])["song"]
        timing = {"durationMs": 60200, "beatsMs": [400, 900]}
        levels = {"startMs": 300, "stepMs": 500, "momentaryLufs": [-21, -19],
                  "firstSoundMs": 0, "lastSoundMs": 60000}
        self.cache.save("song", track, timing)
        with patch("worker.measure", return_value=levels) as measure_levels:
            work = Work(self.catalog, self.cache, measure_levels=measure_levels)
            self.assertEqual(work.request(["song"], {1}), {"tracks": {"song": timing}, "pending": ["song"]})
            work.process("song", lambda _: self.fail("an existing beat grid must not be reanalyzed"))
            measure_levels.assert_called_once_with(self.audio, 60200)
            self.assertEqual(work.request(["song"], {1}),
                             {"tracks": {"song": {**timing, "audioLevels": levels}}, "pending": []})

    def test_bass_entry_backfill_keeps_ready_grid_available(self):
        track = self.catalog.tracks(["song"])["song"]
        timing = {"durationMs": 60200, "beatsMs": [400, 900], "beatOffsetsMs": [18, None]}
        bass_entries = {"version": 2, "downbeatsMs": []}
        timing["bassEntries"] = {"version": 1, "downbeatsMs": []}
        self.cache.save("song", track, timing)
        with patch("worker.beat_features", return_value=([18, None], bass_entries)) as detector:
            work = Work(self.catalog, self.cache, measure_beats=detector)
            self.assertEqual(work.request(["song"], {1}), {"tracks": {"song": timing}, "pending": ["song"]})
            work.process("song", lambda _: self.fail("an existing beat grid must not be reanalyzed"))
            detector.assert_called_once_with(self.audio, [400, 900], [])
            self.assertEqual(work.request(["song"], {1}),
                             {"tracks": {"song": {**timing, "bassEntries": bass_entries}}, "pending": []})

    def test_failed_audio_levels_are_retried_without_hiding_the_grid(self):
        track = self.catalog.tracks(["song"])["song"]
        timing = {"durationMs": 60200, "beatsMs": [400, 900]}
        self.cache.save("song", track, timing)
        with patch("worker.measure", side_effect=OSError) as decoder, patch("worker.time.time", return_value=1000) as clock:
            work = Work(self.catalog, self.cache, measure_levels=decoder)
            work.process("song", lambda _: self.fail("an existing beat grid must not be reanalyzed"))
            self.assertEqual(work.request(["song"], {1}), {"tracks": {"song": timing}, "pending": []})
            clock.return_value = 4600
            self.assertEqual(work.request(["song"], {1}), {"tracks": {"song": timing}, "pending": ["song"]})

    @unittest.skipUnless(shutil.which("ffmpeg"), "FFmpeg is required to test the audio profile")
    def test_audio_levels_measure_actual_quiet_edges_and_loudness_change(self):
        sample_rate = 8000
        path = self.audio.with_suffix(".wav")
        signal = array("h", (int(amplitude * math.sin(2 * math.pi * 440 * n / sample_rate))
                            for amplitude in (0, 10000, 10000, 1000, 1000, 0)
                            for n in range(sample_rate)))
        with wave.open(str(path), "wb") as wav:
            wav.setparams((1, 2, sample_rate, 0, "NONE", "not compressed"))
            wav.writeframes(signal.tobytes())
        result = measure(path, 6000)
        self.assertAlmostEqual(result["firstSoundMs"], 1000, delta=20)
        self.assertAlmostEqual(result["lastSoundMs"], 5000, delta=20)
        self.assertEqual(result["stepMs"], 500)
        self.assertGreater(result["momentaryLufs"][4] - result["momentaryLufs"][8], 18)

    @unittest.skipUnless(shutil.which("ffmpeg"), "FFmpeg is required to test bass entries")
    def test_marks_a_sustained_bass_entry_and_ignores_a_single_hit(self):
        sample_rate = 8000
        path = self.audio.with_suffix(".wav")
        signal = array("h", [0]) * (10 * sample_rate)
        for time_ms in (2000, 5500, 6000, 6500, 7500, 8000, 8500, 9000, 9500):
            start = time_ms * sample_rate // 1000
            for n in range(120 * sample_rate // 1000):
                signal[start + n] = int(13000 * math.sin(2 * math.pi * 80 * n / sample_rate))
        with wave.open(str(path), "wb") as wav:
            wav.setparams((1, 2, sample_rate, 0, "NONE", "not compressed"))
            wav.writeframes(signal.tobytes())
        offsets, entries = beat_features(path, list(range(0, 10_000, 500)), [0, 2000, 4000, 6020, 8000])
        self.assertEqual(entries, {"version": 2, "downbeatsMs": [6020]})
        self.assertIsNotNone(offsets[4])

    def test_http_only_queues_tracks_visible_to_the_authenticated_user(self):
        calls = []

        def navidrome(method, auth):
            calls.append((method, auth))
            return {"status": "ok", "musicFolders": {"musicFolder": [{"id": "1"}]}}

        endpoint = "/music/rest/platterAutoMix"
        handler = partial(Handler, work=self.work, navidrome=SimpleNamespace(request=navidrome), endpoint=endpoint)
        with ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{server.server_port}{endpoint}?id=song&id=private"
                with urlopen(url, timeout=2) as response:
                    self.assertEqual(json.load(response)["subsonic-response"]["status"], "failed")
                self.assertEqual(calls, [])
                with urlopen(url + "&u=listener&t=token&s=salt", timeout=2) as response:
                    self.assertEqual(json.load(response)["subsonic-response"]["platterAutoMix"]["pending"], ["song"])
                self.assertEqual(calls, [("getMusicFolders", {"u": "listener", "t": "token", "s": "salt"})])
                self.assertEqual(list(self.work.priority), ["song"])
            finally:
                server.shutdown()
                thread.join()

    def test_smart_fade_compares_the_given_pair_in_its_original_order(self):
        client = AudioMuse("https://audiomuse.example", server="Music")
        cues = {"results": [{"item_id": "song", "fade_out_seconds": 57.3},
                            {"item_id": "other", "fade_in_seconds": 2.5}]}
        plan = {"type": "beat_mix", "start": 54, "duration": 8, "to": {"start": 2.5, "bass_db": [-24, 0]}}
        with patch.object(client, "read", side_effect=[cues, plan]) as read:
            self.assertEqual(client.transition("song", "other"), plan)
            self.assertEqual(read.call_args_list, [
                call("/api/recording_search/smart_fade",
                     body={"item_ids": ["song", "other"], "keep_order": True, "server": "Music"}, timeout=30),
                call("/api/recording_search/transition",
                     params={"from_id": "song", "to_id": "other", "out": 57.3, "in": 2.5, "server": "Music"}, timeout=90),
            ])
        cues["results"][1]["fade_in_seconds"] = None
        with patch.object(client, "read", return_value=cues) as read:
            with self.assertRaisesRegex(ValueError, "no Smart Fade cues"):
                client.transition("song", "other")
            read.assert_called_once()

    def test_audiomuse_uses_provider_ids_and_keeps_cached_timing_when_unavailable(self):
        calls = []

        class MuseHandler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                url = urlsplit(self.path)
                calls.append((url.path, parse_qs(url.query), self.headers.get("Authorization")))
                if parse_qs(url.query).get("ids") == ["other"]:
                    self.send_error(503)
                    return
                body = json.dumps({"provider_type": "navidrome", "tracks": [
                    {"id": "song", "tempo": 126.4, "key": "F#", "scale": "minor", "energy": 0.76, "embedding": [1]},
                    {"id": "private", "tempo": 150},
                ]}).encode()
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)

        timing = {"durationMs": 60200, "beatsMs": [400, 900]}
        for track_id, track in self.catalog.tracks(["song", "other", "private"]).items():
            self.cache.save(track_id, track, timing)
        with ThreadingHTTPServer(("127.0.0.1", 0), MuseHandler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                client = AudioMuse(f"http://127.0.0.1:{server.server_port}/muse", "test-token", "Music")
                work = Work(self.catalog, self.cache, client)
                expected = {**timing, "audioMuse": {"tempoBpm": 126.4, "key": "F#", "scale": "minor", "energy": 0.76}}
                self.assertEqual(work.request(["song", "private"], {1})["tracks"], {"song": expected})
                self.assertEqual(work.request(["song"], {1})["tracks"], {"song": expected})
                self.assertEqual(calls, [
                    ("/muse/api/sync", {"ids": ["song"], "include_embeddings": ["false"], "limit": ["1"],
                                        "server": ["Music"]}, "Bearer test-token"),
                ])
                self.assertEqual(work.request(["other"], {1})["tracks"], {"other": timing})
                self.assertEqual(work.request(["other", "song"], {1})["tracks"], {"other": timing, "song": expected})
                self.assertEqual(len(calls), 2)
                self.assertEqual(self.work.request(["song"], {1})["tracks"], {"song": timing})
            finally:
                server.shutdown()
                thread.join()
