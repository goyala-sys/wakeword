"""Digital gain in front of the VAD and spotter.

The phone mic runs without platform AGC, so speech from 2-4 m arrives 20-30 dB quieter
than the close-talk audio the model was trained on (README "Distance"). This tracks the
loudest recent chunk (instant attack, slow release) and scales it to `target_db`, never
attenuating and never above `max_gain_db`. Gain drops immediately so a loud onset isn't
clipped, and rises gradually (ramped per sample) so it doesn't pump inside a word.
Mirrors com.findmyphone.wakeword.core.Agc.
"""
from __future__ import annotations

import numpy as np

SAMPLE_RATE = 16000
UP_RATE = 0.3  # fraction of the remaining gap closed per chunk when gain rises


def db_to_amp(db: float) -> float:
    return 10 ** (db / 20)


def amp_to_db(a: float) -> float:
    return 20 * np.log10(a + 1e-9)


class Agc:
    def __init__(self, target_db: float = -22.0, max_gain_db: float = 30.0, release_s: float = 4.0):
        self.target = db_to_amp(target_db)
        self.max_gain = db_to_amp(max_gain_db)
        self.release_s = release_s
        self.env = db_to_amp(-40)
        self.gain = 1.0

    @property
    def gain_db(self) -> float:
        return amp_to_db(self.gain)

    def process(self, x: np.ndarray) -> np.ndarray:
        if len(x) == 0:
            return x
        r = float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) + 1e-9
        rel = np.exp(-len(x) / SAMPLE_RATE / self.release_s)
        self.env = r if r > self.env else rel * self.env + (1 - rel) * r
        want = min(self.max_gain, max(1.0, self.target / self.env))
        if want < self.gain:
            start = end = want
        else:
            start, end = self.gain, self.gain + UP_RATE * (want - self.gain)
        self.gain = end
        ramp = start + (end - start) / len(x) * np.arange(1, len(x) + 1)
        return np.clip(x * ramp, -1, 1).astype(np.float32)
