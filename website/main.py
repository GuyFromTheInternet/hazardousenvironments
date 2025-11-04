#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Multi-model Google Gemini analyzer with resume/recovery, per-model cooldown,
retry-on-empty-results behavior, and compatibility with legacy debug filenames
like `resp_240` (with or without `.txt`).

- Recognizes legacy files `resp_<n>` and `resp_<n>.txt` when reconstructing.
- Still saves detailed debug files `resp_001__provider__model__attempt1.txt`.
- When saving new debug files, also creates legacy `resp_<n>` and `resp_<n>.txt`
  if they don't already exist (to help older versions/tools).
"""

from __future__ import annotations
import os
import re
import io
import json
import time
import base64
import typing as t
import logging
import argparse
from logging.handlers import RotatingFileHandler
import tempfile
import shutil

import requests
from requests.exceptions import RequestException, HTTPError

# Google Gen AI SDK (Gemini)
try:
    from google import genai
    from google.genai import types
    from google.genai.errors import APIError
except Exception:
    raise SystemExit("Please install google-genai: pip install google-genai")

# Pillow for image conversions
try:
    from PIL import Image
    try:
        import pillow_avif  # optional plugin
    except Exception:
        pass
except Exception:
    raise SystemExit("Please install Pillow: pip install pillow pillow-avif-plugin")

# ---------------- Config ----------------
GCP_API_KEY = os.getenv("GEMINI_API_KEY", "AIzaSyA9UAdRdpkApV_jBx82gMq4kJ1QnrNt1hs")

INPUT_PATH_DEFAULT = "data.json"
OUTPUT_PATH_DEFAULT = "data-modified.json"
DEBUG_RESPONSES_DIR = "debug_responses"
LOG_FILE = "process.log"

MAX_IMAGES_PER_PLACE = 1
SUPPORTED_IMAGE_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}
RETRY_MAX_ATTEMPTS = 3
RETRY_BASE_SLEEP_S = 1.0

# Model pool (Google-only). Order determines rotation.
MODEL_POOL = [
    {"provider": "google", "model": "gemini-flash-latest"},
    {"provider": "google", "model": "gemini-flash-lite-latest"},
    {"provider": "google", "model": "gemini-2.5-pro"},
]

# Per-model cooldown in seconds after a rate-limit / transient failure
COOLDOWN_SECS = 60

# Maximum time to wait if all models are in cooldown (seconds)
MAX_WAIT_IF_ALL_COOLDOWN = 15

# ---------------- Logging ----------------
def setup_logging(debug: bool = False):
    level = logging.DEBUG if debug else logging.INFO
    root = logging.getLogger()
    root.setLevel(level)

    # If handlers already exist (re-run in same process), skip adding duplicates
    if not root.handlers:
        ch = logging.StreamHandler()
        ch.setLevel(level)
        ch.setFormatter(logging.Formatter("%(asctime)s | %(levelname)s | %(message)s", "%Y-%m-%d %H:%M:%S"))
        root.addHandler(ch)

        fh = RotatingFileHandler(LOG_FILE, maxBytes=5_000_000, backupCount=3, encoding="utf-8")
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter("%(asctime)s | %(levelname)s | %(name)s | %(message)s", "%Y-%m-%d %H:%M:%S"))
        root.addHandler(fh)


logger = logging.getLogger(__name__)

# ---------------- Utilities ----------------
_DATA_URL_RE = re.compile(r"^data:(?P<mime>[^;]+);base64,(?P<b64>.+)$", re.IGNORECASE)


def load_places(path: str) -> t.List[dict]:
    if not os.path.exists(path):
        logger.error("Input file '%s' not found.", path)
        raise FileNotFoundError(path)
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read().strip()
    data = json.loads(raw)
    if isinstance(data, dict):
        return [data]
    if not isinstance(data, list):
        raise ValueError("Top-level JSON must be a list")
    return data


def parse_image_source(s: str) -> tuple[str, bytes]:
    s = s.strip()
    m = _DATA_URL_RE.match(s)
    if m:
        mime = m.group("mime").lower()
        b64 = m.group("b64")
        try:
            data = base64.b64decode(b64, validate=True)
        except Exception as e:
            raise ValueError("Invalid base64 in data URL") from e
        return mime, data
    if s.lower().startswith(("http://", "https://")):
        try:
            resp = requests.get(s, timeout=20)
            resp.raise_for_status()
        except RequestException as e:
            raise ValueError(f"Failed to fetch image URL: {s}") from e
        mime = resp.headers.get("Content-Type", "").split(";")[0].lower() or "application/octet-stream"
        return mime, resp.content
    raise ValueError("Unsupported image source format (must be data URL or http(s) URL).")


def ensure_supported_image(mime: str, data: bytes) -> tuple[str, bytes]:
    if mime in SUPPORTED_IMAGE_MIME_TYPES:
        return mime, data
    # Try to transcode with PIL
    try:
        img = Image.open(io.BytesIO(data))
        if img.mode not in ("RGB", "L"):
            img = img.convert("RGB")
        out = io.BytesIO()
        img.save(out, format="JPEG", quality=90, optimize=True)
        out.seek(0)
        return "image/jpeg", out.read()
    except Exception as e:
        raise ValueError(f"Could not convert image ({mime}) to JPEG: {e}") from e


def clamp_rating(x: t.Any) -> float:
    try:
        v = float(x)
    except Exception:
        return 0.0
    return max(0.0, min(10.0, v))


def normalize_floors(x: t.Any) -> t.Union[int, float, None]:
    if x is None:
        return None
    try:
        v = float(x)
    except Exception:
        return None
    if v < 0:
        return 0
    if abs(v - round(v)) < 1e-6:
        return int(round(v))
    return v


def atomic_write(path: str, data: str):
    """Write data to a temp file and atomically replace the target."""
    dirn = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(dir=dirn, prefix=".tmp_write_", text=True)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(data)
    shutil.move(tmp, path)


def build_response_schema() -> dict:
    rating_schema = {"type": "NUMBER", "minimum": 0, "maximum": 10}
    floors_schema = {"type": "NUMBER", "minimum": 0}
    return {
        "type": "OBJECT",
        "properties": {
            "title": {"type": "STRING"},
            "description": {"type": "STRING"},
            "address": {"type": "STRING"},
            "lat": {"type": "NUMBER"},
            "lon": {"type": "NUMBER"},
            "url": {"type": "STRING"},
            "date": {"type": "STRING"},
            "Охраняемость": rating_schema,
            "Заполненость интерьера": rating_schema,
            "Давность здания": rating_schema,
            "Общий рейтинг(0-10 stars)": rating_schema,
            "Этажей": floors_schema,
        },
    }


def build_prompt_text(place: dict) -> str:
    requested_format = (
        '{\n'
        '  "title": "",\n'
        '  "description":"",\n'
        '  "address": "",\n'
        '  "lat":,\n'
        '  "lon":,\n'
        '  "url": "",\n'
        '  "date": "",\n'
        '  "Охраняемость": ,\n'
        '  "Заполненость интерьера": ,\n'
        '  "Давность здания": ,\n'
        '  "Общий рейтинг(0-10 stars)": ,\n'
        '  "Этажей": \n'
        '}'
    )
    instruction = (
        "Analyses place from this json. and convert it into such format:\n"
        f"{requested_format}\n\n"
        "Each new parameter should be rated from 0-10. Охраняемость is bad, so more is worse. "
        "Also provide 'Этажей' (number of floors) as a number — predict it from the image when description doesn't say. "
        "Analyse description and image (predict by image how many floors the place has if description doesn't provide such info). "
        "As output, provide only new json with added parameters, without anything else. Only json. "
        "Preserve the original values for title, description (if present), address, lat, lon, url, date exactly as given; "
        "only add the rating fields and 'Этажей'."
    )
    place_blob = json.dumps(place, ensure_ascii=False)
    return f"{instruction}\n\nPLACE_JSON:\n{place_blob}"


def save_debug_response(index: int, raw_text: str, provider: str, model: str, attempt: int):
    os.makedirs(DEBUG_RESPONSES_DIR, exist_ok=True)
    # safe filename: replace slashes/colons with underscores
    model_safe = model.replace("/", "_").replace(":", "_")
    detailed_fname = os.path.join(DEBUG_RESPONSES_DIR, f"resp_{index:03d}__{provider}__{model_safe}__attempt{attempt}.txt")
    try:
        with open(detailed_fname, "w", encoding="utf-8") as f:
            f.write(raw_text)
        logger.debug("Saved detailed raw model response to %s", detailed_fname)
    except Exception:
        logger.exception("Failed to save detailed debug response to %s", detailed_fname)

    # Also write legacy filenames so old runs/tools can pick them up:
    legacy_fname_txt = os.path.join(DEBUG_RESPONSES_DIR, f"resp_{index}.txt")
    legacy_fname_plain = os.path.join(DEBUG_RESPONSES_DIR, f"resp_{index}")
    # Only create legacy files if they don't already exist (preserve old ones)
    try:
        if not os.path.exists(legacy_fname_txt):
            with open(legacy_fname_txt, "w", encoding="utf-8") as f:
                f.write(raw_text)
            logger.debug("Saved legacy debug response to %s", legacy_fname_txt)
    except Exception:
        logger.exception("Failed to save legacy debug response to %s", legacy_fname_txt)
    try:
        if not os.path.exists(legacy_fname_plain):
            # create a no-extension file
            with open(legacy_fname_plain, "w", encoding="utf-8") as f:
                f.write(raw_text)
            logger.debug("Saved legacy debug response to %s", legacy_fname_plain)
    except Exception:
        logger.exception("Failed to save legacy debug response to %s", legacy_fname_plain)


# ---------------- Model handlers ----------------
class RateLimitError(Exception):
    pass


class ModelPool:
    def __init__(self, pool: t.List[dict], cooldown_map: dict[str, float]):
        self.pool = pool
        self._idx = 0
        self.size = len(pool)
        self.cooldown_map = cooldown_map

    def _model_key(self, entry: dict) -> str:
        return f"{entry['provider']}:{entry['model']}"

    def get_next(self) -> dict:
        """
        Return next non-cooled model entry. If all are in cooldown, wait a short while
        (up to MAX_WAIT_IF_ALL_COOLDOWN) and then return the one with earliest cooldown expiry.
        """
        now = time.time()
        for _ in range(self.size):
            entry = self.pool[self._idx]
            key = self._model_key(entry)
            self._idx = (self._idx + 1) % self.size
            until = self.cooldown_map.get(key, 0)
            if until <= now:
                return entry
        # all are cooled; find earliest expiry
        if self.cooldown_map:
            earliest_key, earliest_until = min(self.cooldown_map.items(), key=lambda kv: kv[1])
        else:
            earliest_until = now
        wait = max(0.1, min(MAX_WAIT_IF_ALL_COOLDOWN, earliest_until - now))
        logger.info("All models are cooled. Waiting %.1fs for earliest cooldown to expire.", wait)
        time.sleep(wait)
        # After sleeping, return the next one in rotation
        entry = self.pool[self._idx]
        self._idx = (self._idx + 1) % self.size
        return entry


def analyze_with_google(client: genai.Client, model_name: str, place: dict) -> tuple[dict, str]:
    parts: t.List[t.Union[str, types.Part]] = []
    imgs = place.get("images") or []
    for s in imgs[:MAX_IMAGES_PER_PLACE]:
        try:
            mime, data = parse_image_source(str(s))
            mime, data = ensure_supported_image(mime, data)
            parts.append(types.Part.from_bytes(data=data, mime_type=mime))
            logger.debug("Google: attached image mime=%s size=%d", mime, len(data))
        except Exception as e:
            logger.warning("Google: skipping image due to: %s", e)
    parts.append(build_prompt_text(place))

    cfg = types.GenerateContentConfig(
        temperature=0.2,
        response_mime_type="application/json",
        response_schema=build_response_schema(),
        max_output_tokens=1024,
    )

    # Wrap call in retry loop for transient issues
    last_exc = None
    for attempt in range(1, RETRY_MAX_ATTEMPTS + 1):
        try:
            resp = client.models.generate_content(model=model_name, contents=parts, config=cfg)
            raw = (resp.text or "").strip()
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                start = raw.find("{")
                end = raw.rfind("}")
                if start != -1 and end != -1 and end > start:
                    candidate = raw[start : end + 1]
                    parsed = json.loads(candidate)
                else:
                    parsed = {"_raw_text": raw}
            return parsed, raw
        except APIError as e:
            last_exc = e
            # Treat APIError as transient/rate-limit; let caller cooldown this model
            logger.exception("Google APIError on model %s attempt %d: %s", model_name, attempt, e)
            time.sleep(RETRY_BASE_SLEEP_S * (2 ** (attempt - 1)))
            continue
        except Exception as e:
            last_exc = e
            logger.exception("Unexpected error when calling Google model %s attempt %d: %s", model_name, attempt, e)
            time.sleep(RETRY_BASE_SLEEP_S * (2 ** (attempt - 1)))
            continue
    # After retries, raise the last exception so caller can apply cooldown
    raise last_exc if last_exc is not None else Exception("Unknown failure in analyze_with_google")


# ---------------- Helper: detect empty outputs ----------------
def is_empty_analysis(parsed: dict) -> bool:
    """
    Consider the parsed response 'empty' if:
      - parsed is not a dict or contains _raw_text only -> empty
      - all four rating fields (Охраняемость, Заполненость интерьера, Давность здания, Общий рейтинг(0-10 stars))
        are present or assumed 0 and equal to 0.0
      - AND "Этажей" is either missing or None
    """
    if not isinstance(parsed, dict):
        return True
    if "_raw_text" in parsed and len(parsed) == 1:
        return True

    rating_keys = [
        "Охраняемость",
        "Заполненость интерьера",
        "Давность здания",
        "Общий рейтинг(0-10 stars)",
    ]
    all_zero = True
    for k in rating_keys:
        v = parsed.get(k, 0)
        try:
            if float(v) != 0.0:
                all_zero = False
                break
        except Exception:
            all_zero = False
            break

    floors = parsed.get("Этажей", None)
    floors_missing_or_none = floors is None

    return all_zero and floors_missing_or_none


# ---------------- Orchestrator with cooldown and resume ----------------
def analyze_with_pool(client_google: genai.Client, pool: ModelPool, cooldown_map: dict[str, float], place: dict, idx: int) -> dict:
    """Try Google models until one yields a non-empty parsed JSON or all have been attempted."""
    attempted_keys: list[str] = []
    attempt_counter = 0

    for _ in range(pool.size):
        entry = pool.get_next()
        provider = entry["provider"]
        model_name = entry["model"]
        model_key = f"{provider}:{model_name}"
        now = time.time()
        if cooldown_map.get(model_key, 0) > now:
            logger.debug("Skipping cooled model %s (cooldown until %.1f)", model_key, cooldown_map[model_key])
            continue

        attempt_counter += 1
        logger.info("Place %d: using %s (try %d)", idx, model_key, attempt_counter)
        try:
            parsed, raw = analyze_with_google(client_google, model_name, place)
            save_debug_response(idx, raw, provider, model_name, attempt_counter)

            if is_empty_analysis(parsed):
                logger.warning("Place %d: model %s returned empty analysis; trying next model.", idx, model_key)
                attempted_keys.append(model_key + "(empty)")
                time.sleep(0.3)
                continue

            return parsed

        except APIError as ae:
            logger.warning("Place %d: Google APIError on %s: %s", idx, model_key, ae)
            cooldown_map[model_key] = time.time() + COOLDOWN_SECS
            attempted_keys.append(model_key + "(api_error)")
            time.sleep(0.5)
            continue
        except Exception as e:
            logger.exception("Place %d: unexpected error on %s: %s", idx, model_key, e)
            cooldown_map[model_key] = time.time() + COOLDOWN_SECS
            attempted_keys.append(model_key + "(error)")
            time.sleep(0.5)
            continue

    logger.error("Place %d: all Google models attempted and none produced usable output: %s", idx, attempted_keys)
    # Try to return the most recent debug file for this index (legacy or detailed)
    if os.path.isdir(DEBUG_RESPONSES_DIR):
        candidates = [f for f in os.listdir(DEBUG_RESPONSES_DIR) if re.match(rf"^resp_{idx}(?:__.*)?(?:\.txt)?$", f) or re.match(rf"^resp_{idx}$", f)]
        if candidates:
            # pick the most-recent by modification time
            candidates_full = [(f, os.path.getmtime(os.path.join(DEBUG_RESPONSES_DIR, f))) for f in candidates]
            candidates_full.sort(key=lambda kv: kv[1])
            last_fname = candidates_full[-1][0]
            try:
                with open(os.path.join(DEBUG_RESPONSES_DIR, last_fname), "r", encoding="utf-8") as f:
                    raw = f.read()
                try:
                    return json.loads(raw)
                except Exception:
                    start = raw.find("{")
                    end = raw.rfind("}")
                    if start != -1 and end != -1 and end > start:
                        try:
                            return json.loads(raw[start : end + 1])
                        except Exception:
                            pass
                    return {"_raw_text": raw}
            except Exception:
                logger.exception("Failed to read debug file %s", last_fname)
                return {"_raw_text": "No usable parsed output; all models returned empty or errors."}
    return {"_raw_text": "No usable parsed output; all models returned empty or errors."}


def merge_with_original(place: dict, model_json: dict) -> dict:
    final = {
        "title": place.get("title", ""),
        "description": place.get("description", ""),
        "address": place.get("address", ""),
        "lat": place.get("lat"),
        "lon": place.get("lon"),
        "url": place.get("url", ""),
        "date": place.get("date", ""),
    }
    keys = [
        "Охраняемость",
        "Заполненость интерьера",
        "Давность здания",
        "Общий рейтинг(0-10 stars)",
    ]
    for k in keys:
        final[k] = clamp_rating(model_json.get(k, 0))
    final["Этажей"] = normalize_floors(model_json.get("Этажей", None))
    return final


# ---------------- Resume / Recovery Helpers ----------------
def list_debug_resp_indices() -> t.List[int]:
    """
    Return sorted list of indices that have debug response files present.
    Matches both new detailed files like:
      resp_001__provider__model__attempt1.txt
    and legacy files like:
      resp_240
      resp_240.txt
    """
    if not os.path.isdir(DEBUG_RESPONSES_DIR):
        return []
    out: t.List[int] = []
    for name in os.listdir(DEBUG_RESPONSES_DIR):
        # Match either:
        #  - resp_<number>__...(.txt)
        #  - resp_<number>.txt
        #  - resp_<number>  (legacy)
        m = re.match(r"^resp_(\d+)(?:__.*)?(?:\.txt)?$", name)
        if m:
            out.append(int(m.group(1)))
            continue
        # fallback exact legacy form (should already be covered, but keep just in case)
        m2 = re.match(r"^resp_(\d+)$", name)
        if m2:
            out.append(int(m2.group(1)))
    out = sorted(set(out))
    return out


def load_parsed_from_debug(idx: int) -> dict:
    """
    Load the most relevant debug file for index `idx` and attempt to parse JSON.
    Works with both new detailed filenames and legacy filenames.
    """
    if not os.path.isdir(DEBUG_RESPONSES_DIR):
        return {}
    candidates = [f for f in os.listdir(DEBUG_RESPONSES_DIR) if re.match(rf"^resp_{idx}(?:__.*)?(?:\.txt)?$", f) or re.match(rf"^resp_{idx}$", f)]
    if not candidates:
        return {}
    # pick the most recent by modification time
    candidates_full = [(f, os.path.getmtime(os.path.join(DEBUG_RESPONSES_DIR, f))) for f in candidates]
    candidates_full.sort(key=lambda kv: kv[1])
    fname = candidates_full[-1][0]
    try:
        with open(os.path.join(DEBUG_RESPONSES_DIR, fname), "r", encoding="utf-8") as f:
            raw = f.read()
    except Exception:
        return {"_raw_text": ""}
    # try parse JSON
    try:
        parsed = json.loads(raw)
        return parsed
    except Exception:
        start = raw.find("{")
        end = raw.rfind("}")
        if start != -1 and end != -1 and end > start:
            try:
                return json.loads(raw[start : end + 1])
            except Exception:
                pass
    return {"_raw_text": raw}


# ---------------- Main ----------------
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("-i", "--input", default=INPUT_PATH_DEFAULT)
    parser.add_argument("-o", "--output", default=OUTPUT_PATH_DEFAULT)
    parser.add_argument("-v", "--debug", action="store_true")
    args = parser.parse_args()

    setup_logging(debug=args.debug)
    logger.info("Starting. input=%s output=%s", args.input, args.output)
    logger.info("Google models in pool: %s", [m["model"] for m in MODEL_POOL])

    client_google = genai.Client(api_key=GCP_API_KEY)
    cooldown_map: dict[str, float] = {}

    places = load_places(args.input)
    total = len(places)
    logger.info("Loaded %d places", total)

    # Reconstruct 'results' from existing output file or from debug responses
    results: t.List[dict] = []
    if os.path.exists(args.output):
        try:
            with open(args.output, "r", encoding="utf-8") as f:
                existing = json.load(f)
            if isinstance(existing, list):
                results = existing
                logger.info("Loaded %d already-processed entries from %s", len(results), args.output)
            else:
                logger.warning("Existing output file %s is not a list; ignoring it.", args.output)
                results = []
        except Exception:
            logger.exception("Failed to read existing output file %s; will try debug_responses.", args.output)
            results = []

    # If no output file or not enough, try reconstructing from debug responses
    processed_indices = list_debug_resp_indices()
    if processed_indices:
        max_idx = max(processed_indices)
        if len(results) < max_idx:
            logger.info("Reconstructing entries from debug_responses up to index %d", max_idx)
            reconstructed = results[:]  # copy existing ones
            for idx in range(len(reconstructed) + 1, max_idx + 1):
                parsed = load_parsed_from_debug(idx)
                place = places[idx - 1] if idx - 1 < len(places) else {}
                final_obj = merge_with_original(place, parsed)
                reconstructed.append(final_obj)
                logger.debug("Reconstructed index %d from debug file", idx)
            results = reconstructed
            try:
                atomic_write(args.output, json.dumps(results, ensure_ascii=False, indent=2))
                logger.info("Wrote reconstructed %d entries to %s", len(results), args.output)
            except Exception:
                logger.exception("Failed to write reconstructed output to %s", args.output)

    start_idx = len(results) + 1
    if start_idx > total:
        logger.info("Nothing to do: start index %d > total %d", start_idx, total)
        return

    os.makedirs(DEBUG_RESPONSES_DIR, exist_ok=True)
    pool = ModelPool(MODEL_POOL.copy(), cooldown_map)

    # Process remaining items one-by-one with incremental saving
    for idx in range(start_idx, total + 1):
        place = places[idx - 1].copy()
        for key in ("title", "address", "lat", "lon", "url", "date"):
            place.setdefault(key, "" if key not in ("lat", "lon") else None)

        logger.info("Processing %d/%d: %s", idx, total, place.get("title") or "<no title>")
        t0 = time.time()
        parsed = analyze_with_pool(client_google, pool, cooldown_map, place, idx)
        final_obj = merge_with_original(place, parsed)
        results.append(final_obj)

        # save debug response already happened inside analyze_with_pool (save_debug_response).
        # Write incremental output atomically
        try:
            atomic_write(args.output, json.dumps(results, ensure_ascii=False, indent=2))
            logger.info("Saved %d/%d results to %s", len(results), total, args.output)
        except Exception:
            logger.exception("Failed to write output file after index %d", idx)

        elapsed = time.time() - t0
        logger.info("Completed %d in %.2f s", idx, elapsed)

    logger.info("All done. Processed %d entries. Final output: %s", len(results), args.output)


if __name__ == "__main__":
    main()