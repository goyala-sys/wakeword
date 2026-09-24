"""Simulated distance: how recall falls as the speaker moves away from the phone.

Distance = quieter speech (-6 dB per doubling) + more room echo (lower direct-to-reverb
ratio) over a fixed quiet-room noise floor. Levels are estimates for a phone mic in
VOICE_RECOGNITION mode; calibrate them with the Android log's "utterance ... peak" lines.

    python -m eval.distance ../data/hey_buddy          # AGC off vs on
"""
from __future__ import annotations

import argparse
import json
from multiprocessing import Pool
from pathlib import Path

import numpy as np

from wakeword.engine import EngineConfig, WakeWordEngine
from wakeword.keywords import Keyword
from wakeword.sources import chunks, load_wav

from .evaluate import MATCH_AFTER_END_S

SR = 16000
# name -> (speech RMS dBFS, direct-to-reverb ratio dB); living room, RT60 0.5 s
CONDITIONS = {
    "0.3 m": (-26, 12),
    "1 m": (-36, 3),
    "2 m": (-42, -2),
    "4 m": (-48, -7),
    "next room": (-54, -10),
}
RT60_S = 0.5
NOISE_FLOOR_DB = -66


def _rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(x.astype(np.float64) ** 2)) + 1e-12)


def _rir(drr_db: float, rng) -> np.ndarray:
    """Direct path + exponentially decaying diffuse tail."""
    n = int(RT60_S * SR * 1.2)
    tail = rng.standard_normal(n) * np.exp(-6.9 * np.arange(n) / SR / RT60_S)
    tail[: int(0.003 * SR)] = 0
    h = tail / np.sqrt(np.sum(tail ** 2)) * 10 ** (-drr_db / 20)
    h[0] = 1.0
    return h


def degrade(x: np.ndarray, level_db: float, drr_db: float, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    active = np.abs(x) > 1e-4
    ref = _rms_db(x[active]) if active.any() else _rms_db(x)
    h = _rir(drr_db, rng)
    n = len(x) + len(h) - 1
    y = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(h, n), n)[: len(x)]
    y *= 10 ** ((level_db - ref) / 20)
    noise = rng.standard_normal(len(y))
    y += noise / np.std(noise) * 10 ** (NOISE_FLOOR_DB / 20)
    return np.clip(y, -1, 1).astype(np.float32)


def _job(args):
    data, cond, agc = args
    meta = json.loads((data / "truth.json").read_text())
    level, drr = CONDITIONS[cond]
    cfg = EngineConfig([Keyword(meta["keyword"])], agc=agc)
    pos = WakeWordEngine(cfg).run(chunks(degrade(load_wav(str(data / "pos_clean.wav")), level, drr, 0)))
    hit = [False] * len(meta["positives"])
    stray = 0
    for d in pos:
        for i, p in enumerate(meta["positives"]):
            if not hit[i] and p["start"] <= d.time_s <= p["end"] + MATCH_AFTER_END_S:
                hit[i] = True
                break
        else:
            stray += 1
    neg = WakeWordEngine(cfg).run(chunks(degrade(load_wav(str(data / "neg_clean.wav")), level, drr, 7)))
    return cond, agc, sum(hit) / len(hit), len(neg) + stray, meta["neg_seconds"] / 60


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("data", type=Path)
    a = ap.parse_args()
    with Pool() as p:
        rows = p.map(_job, [(a.data, c, agc) for c in CONDITIONS for agc in (False, True)])
    print(f"{'distance':<10} {'AGC':<4} {'recall':>6} {'false alarms':>13}")
    for cond, agc, recall, fa, neg_min in rows:
        print(f"{cond:<10} {'on' if agc else 'off':<4} {recall:>6.0%} {fa:>4} in {neg_min:.0f} min")


if __name__ == "__main__":
    main()
