#!/usr/bin/env python3
"""Serve incremental Platter sync next to a Navidrome instance."""

import argparse
import hashlib
import json
import logging
import sqlite3
import threading
import time
import uuid
from contextlib import closing
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener


ENDPOINT = "/rest/platterTrackChanges"
PAGE_SIZE = 100
RETAIN = 100_000
AUTH_KEYS = ("u", "p", "t", "s", "c", "v")
DERIVED_COLUMNS = {"full_text", "search_participants", "search_normalized", "probe_data", "lyrics", "average_rating"}


def reset_reason(previous, current):
    previous_parts = (previous or "").split(".")
    return "history" if len(previous_parts) == 3 and previous_parts[1] == current.split(".")[1] else "scope"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Navidrome:
    def __init__(self, url):
        parts = urlsplit(url)
        if parts.scheme not in {"http", "https"} or not parts.hostname or parts.username or parts.query or parts.fragment:
            raise ValueError("Use the internal Navidrome HTTP(S) address, including its base path if set")
        self.base = url.rstrip("/") + "/rest/"

    def request(self, method, auth, **params):
        url = self.base + method + "?" + urlencode({**auth, **params, "f": "json"})
        # A redirect can point outside this Navidrome instance and must never carry credentials there.
        with build_opener(ProxyHandler({}), NoRedirect()).open(url, timeout=15) as response:
            body = json.load(response)["subsonic-response"]
        if body.get("status") not in {"ok", "failed"}:
            raise ValueError("Invalid Subsonic response")
        return body


class Journal:
    def __init__(self, source, state, poll_seconds):
        self.source = source.resolve()
        self.state = state.resolve()
        if self.source == self.state or (self.state.exists() and self.source.samefile(self.state)):
            raise ValueError("The journal needs a separate state database")
        self.lock = threading.Lock()
        self.poll_seconds = poll_seconds
        self.next_poll = 0
        with closing(sqlite3.connect(self.state)) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS journal_state (epoch TEXT NOT NULL, initialized INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS known_track (id TEXT PRIMARY KEY, library INTEGER NOT NULL, digest BLOB NOT NULL);
                CREATE TABLE IF NOT EXISTS event (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL,
                    library INTEGER NOT NULL, deleted INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS event_library_seq ON event(library, seq);
            """)
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT epoch FROM journal_state").fetchone():
                db.execute("INSERT INTO journal_state VALUES (?, 0)", (uuid.uuid4().hex,))

    def read_tracks(self):
        # The whole directory, including live WAL and SHM files, must be visible to SQLite.
        with closing(sqlite3.connect(self.source.as_uri() + "?mode=ro", uri=True, timeout=10)) as db:
            schema = {row[1] for row in db.execute("PRAGMA table_info(media_file)")}
            required = {"id", "library_id", "missing", "title"}
            if not required.issubset(schema):
                raise ValueError("Unsupported Navidrome media_file schema")
            columns = sorted(schema - DERIVED_COLUMNS - {"id", "library_id", "missing"})
            fields = ",".join('"' + column.replace('"', '""') + '"' for column in columns)
            rows = db.execute(f"SELECT id, library_id, {fields} FROM media_file WHERE missing = 0")
            return {row[0]: (row[1], hashlib.blake2b(repr(row[2:]).encode(), digest_size=16).digest()) for row in rows}

    def observe(self, db):
        with db:
            db.execute("BEGIN IMMEDIATE")
            tracks = self.read_tracks()
            old = {row[0]: row[1:] for row in db.execute("SELECT id,library,digest FROM known_track")}
            initialized = db.execute("SELECT initialized FROM journal_state").fetchone()[0]
            removed = [(track_id, previous[0]) for track_id, previous in old.items()
                       if track_id not in tracks or tracks[track_id][0] != previous[0]]
            changed = [(track_id, library, digest) for track_id, (library, digest) in tracks.items()
                       if old.get(track_id) != (library, digest)]
            if initialized:
                db.executemany("INSERT INTO event(id,library,deleted) VALUES (?,?,1)", removed)
                db.executemany("INSERT INTO event(id,library,deleted) VALUES (?,?,0)",
                               ((track_id, library) for track_id, library, _ in changed))
            db.executemany("DELETE FROM known_track WHERE id=?", ((track_id,) for track_id, _ in removed))
            db.executemany("INSERT OR REPLACE INTO known_track VALUES (?,?,?)", changed)
            db.execute("UPDATE journal_state SET initialized=1")
            db.execute("DELETE FROM event WHERE seq <= (SELECT seq FROM sqlite_sequence WHERE name='event') - ?", (RETAIN,))

    def page(self, user, libraries, cursor):
        libraries = sorted(set(libraries))
        scope = hashlib.sha256(json.dumps([user, libraries], separators=(",", ":")).encode()).hexdigest()
        with self.lock, closing(sqlite3.connect(self.state)) as db:
            # Force the checks that bracket the initial full download to see the current catalogue.
            if cursor is None or time.monotonic() >= self.next_poll:
                self.observe(db)
                self.next_poll = time.monotonic() + self.poll_seconds
            db.execute("BEGIN")
            epoch = db.execute("SELECT epoch FROM journal_state").fetchone()[0]
            latest = db.execute("SELECT seq FROM sqlite_sequence WHERE name='event'").fetchone()
            head = latest[0] if latest else 0
            prefix = f"{epoch}.{scope}."
            if cursor is None:
                return prefix + str(head), False, False, []
            start = cursor.removeprefix(prefix) if cursor.startswith(prefix) else ""
            if not start.isascii() or not start.isdecimal() or len(start) > 19:
                return prefix + str(head), True, False, []
            sequence = int(start)
            if not max(0, head - RETAIN) <= sequence <= head:
                return prefix + str(head), True, False, []
            if not libraries:
                return prefix + str(head), False, False, []
            placeholders = ",".join("?" for _ in libraries)
            rows = db.execute(f"SELECT seq,id,deleted FROM event WHERE seq > ? AND library IN ({placeholders})"
                              " ORDER BY seq LIMIT ?", [sequence, *libraries, PAGE_SIZE + 1]).fetchall()
            more = len(rows) > PAGE_SIZE
            visible = rows[:PAGE_SIZE]
            next_sequence = visible[-1][0] if more else head
            latest_by_id = {track_id: deleted for _, track_id, deleted in visible}
            return prefix + str(next_sequence), False, more, latest_by_id.items()


class Handler(BaseHTTPRequestHandler):
    def __init__(self, *args, journal, navidrome, endpoint, **kwargs):
        self.journal = journal
        self.navidrome = navidrome
        self.endpoint = endpoint
        super().__init__(*args, **kwargs)

    def log_message(self, _format, *_args):
        # Subsonic credentials are sent in the query string.
        pass

    def send_json(self, body, status=200):
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
            auth = {key: query[key][0] for key in AUTH_KEYS if key in query}
            if not auth.get("u") or not (auth.get("p") or auth.get("t") and auth.get("s")):
                self.send_json({"status": "failed", "error": {"code": 10, "message": "Subsonic credentials required"}})
                return
            body = self.navidrome.request("getMusicFolders", auth)
            if body["status"] != "ok":
                self.send_json(body)
                return
            libraries = [int(folder["id"]) for folder in body["musicFolders"].get("musicFolder", [])]
            previous = query.get("cursor", [None])[0]
            cursor, reset, more, events = self.journal.page(auth.get("u", "").casefold(), libraries, previous)
            changes = []
            for track_id, deleted in events:
                if deleted:
                    changes.append({"id": track_id, "deleted": True})
                    continue
                song = self.navidrome.request("getSong", auth, id=track_id)
                if song["status"] == "ok" and song["song"]["id"] == track_id:
                    changes.append({"id": track_id, "song": song["song"]})
                elif song.get("error", {}).get("code") == 70:
                    changes.append({"id": track_id, "deleted": True})
                else:
                    raise ValueError("Could not read a changed track")
            self.send_json({"status": "ok", "version": "1.16.1", "platterTrackChanges": {
                "cursor": cursor, "reset": reset, "hasMore": more, "changes": changes,
                **({"resetReason": reset_reason(previous, cursor)} if reset else {}),
            }})
        except (OSError, sqlite3.Error, ValueError, KeyError, TypeError) as failure:
            logging.warning("Track journal request failed (%s)", type(failure).__name__)
            self.send_json({"status": "failed", "error": {"code": 0, "message": "Track journal temporarily unavailable"}}, 503)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--navidrome-url", required=True)
    parser.add_argument("--navidrome-db", required=True, type=Path)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=4534)
    parser.add_argument("--poll-seconds", type=int, default=15)
    args = parser.parse_args()
    if not args.navidrome_db.is_file() or args.poll_seconds < 0:
        parser.error("Use an existing Navidrome database and a nonnegative poll interval")
    journal = Journal(args.navidrome_db, args.state, args.poll_seconds)
    navidrome = Navidrome(args.navidrome_url)
    handler = partial(Handler, journal=journal, navidrome=navidrome, endpoint=urlsplit(args.navidrome_url).path.rstrip("/") + ENDPOINT)
    with ThreadingHTTPServer((args.listen, args.port), handler) as server:
        print(f"Platter track journal listening on {args.listen}:{server.server_port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
