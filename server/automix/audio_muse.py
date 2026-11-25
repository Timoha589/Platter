"""Read per-track features from the configured AudioMuse-AI catalogue."""

import argparse
import json
import logging
import math
import os
import threading
import time
from collections import OrderedDict
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def number(value, minimum, maximum):
    if type(value) in (int, float) and minimum <= value <= maximum and math.isfinite(value):
        return value
    return None


def features(row):
    tempo = number(row.get("tempo"), 1, 600)
    energy = number(row.get("energy"), 0, 1)
    key = row.get("key")
    scale = row.get("scale")
    result = {}
    if tempo is not None:
        result["tempoBpm"] = tempo
    if energy is not None:
        result["energy"] = energy
    if isinstance(key, str) and 0 < len(key.strip()) <= 4 and scale in ("major", "minor"):
        result.update(key=key.strip(), scale=scale)
    return result


class AudioMuse:
    def __init__(self, url, token="", server=""):
        address = urlsplit(url)
        if (address.scheme not in ("http", "https") or not address.hostname or address.username is not None
                or address.query or address.fragment):
            raise ValueError("AUDIOMUSE_URL must be an HTTP or HTTPS base address")
        self.url = url.rstrip("/")
        self.headers = {"Accept": "application/json"}
        if token:
            self.headers["Authorization"] = "Bearer " + token
        self.server = server
        self.opener = build_opener(NoRedirect())
        self.cache = OrderedDict()
        self.lock = threading.Lock()
        self.retry_after = 0

    @classmethod
    def configured(cls):
        url = os.getenv("AUDIOMUSE_URL", "").strip()
        return cls(url, os.getenv("AUDIOMUSE_TOKEN", ""), os.getenv("AUDIOMUSE_SERVER", "")) if url else None

    def read(self, path, params=None, body=None, timeout=2):
        url = self.url + path
        if params:
            url += "?" + urlencode(params)
        headers = self.headers
        data = None
        if body is not None:
            headers = {**headers, "Content-Type": "application/json"}
            data = json.dumps(body).encode()
        request = Request(url, data=data, headers=headers)
        with self.opener.open(request, timeout=timeout) as response:
            raw = response.read(262_145)
        if len(raw) > 262_144:
            raise ValueError("AudioMuse response too large")
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("AudioMuse did not return a JSON object")
        return payload

    def get(self, ids):
        now = time.monotonic()
        found = {}
        missing = []
        with self.lock:
            for item in ids:
                cached = self.cache.get(item)
                if cached and cached[0] > now:
                    if cached[1]:
                        found[item] = cached[1]
                else:
                    missing.append(item)
            if not missing or self.retry_after > now:
                return found
        params = {"ids": ",".join(missing), "include_embeddings": "false", "limit": len(missing)}
        if self.server:
            params["server"] = self.server
        try:
            payload = self.read("/api/sync", params=params)
            if payload.get("provider_type") != "navidrome" or not isinstance(payload.get("tracks"), list):
                raise ValueError("AudioMuse did not return a Navidrome catalogue")
            requested = set(missing)
            received = {row["id"]: features(row) for row in payload["tracks"]
                        if isinstance(row, dict) and isinstance(row.get("id"), str) and row["id"] in requested}
        except (OSError, ValueError) as error:
            if isinstance(error, HTTPError):
                error.close()
            with self.lock:
                log_failure = self.retry_after <= time.monotonic()
                self.retry_after = time.monotonic() + 30
            if log_failure:
                logging.warning("AudioMuse metadata unavailable (%s)", getattr(error, "code", type(error).__name__))
            return found
        now = time.monotonic()
        with self.lock:
            self.retry_after = 0
            for item in missing:
                value = received.get(item)
                self.cache[item] = (now + (900 if value else 30), value)
                self.cache.move_to_end(item)
                if value:
                    found[item] = value
            while len(self.cache) > 512:
                self.cache.popitem(last=False)
        return found

    def transition(self, outgoing, incoming):
        selector = {"server": self.server} if self.server else {}
        body = {"item_ids": [outgoing, incoming], "keep_order": True, **selector}
        payload = self.read("/api/recording_search/smart_fade", body=body, timeout=30)
        rows = payload.get("results")
        if (not isinstance(rows, list) or len(rows) != 2 or
                not all(isinstance(row, dict) for row in rows) or
                [row.get("item_id") for row in rows] != [outgoing, incoming]):
            raise ValueError("AudioMuse did not return the requested track pair")
        end = number(rows[0].get("fade_out_seconds"), 0, 86_400)
        start = number(rows[1].get("fade_in_seconds"), 0, 86_400)
        if end is None or start is None:
            raise ValueError("AudioMuse has no Smart Fade cues for this pair")
        params = {"from_id": outgoing, "to_id": incoming, "out": end, "in": start, **selector}
        return self.read("/api/recording_search/transition", params=params, timeout=90)


def main():
    parser = argparse.ArgumentParser(description="Inspect the AudioMuse server or compare a Smart Fade transition.")
    parser.add_argument("ids", nargs="*", metavar="TRACK_ID")
    args = parser.parse_args()
    if len(args.ids) not in (0, 2) or args.ids and args.ids[0] == args.ids[1]:
        parser.error("Give two distinct Navidrome track IDs, or no IDs to check the server version")
    try:
        muse = AudioMuse.configured()
        if muse is None:
            parser.error("Set AUDIOMUSE_URL in the environment")
        version = muse.read("/api/version").get("app_version")
        if not isinstance(version, str) or not version:
            raise ValueError("AudioMuse did not report a version")
        output = {"version": version}
        if args.ids:
            output["profiles"] = muse.get(args.ids)
            output["transition"] = muse.transition(*args.ids)
        print(json.dumps(output, ensure_ascii=True, indent=2))
    except HTTPError as error:
        error.close()
        parser.exit(1, f"AudioMuse returned HTTP {error.code}.\n")
    except (OSError, ValueError) as error:
        detail = str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else type(error).__name__
        parser.exit(1, f"AudioMuse check failed: {detail}.\n")


if __name__ == "__main__":
    main()
