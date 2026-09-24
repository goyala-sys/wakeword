"""The always-on wake-word pipeline.

    mic frames ──► AGC ──► pre-roll ring (0.5 s) ──► VAD gate ──► sherpa KWS stream ──► DetectionGate ──► callbacks
                                              │
                                              └─ no speech for `hangover_s`: pad, flush, drop stream (KWS idle)

The Kotlin service in android/wakeword-android mirrors this structure; keep them in sync.
"""
from __future__ import annotations

import collections
import tempfile
import time
from dataclasses import dataclass
from typing import Callable, Iterable

import numpy as np

from . import models
from .agc import Agc
from .keywords import Keyword, UnigramTokenizer, default_tokenizer
from .vad import SAMPLE_RATE, AlwaysOn, EnergyGate, SileroGate


@dataclass
class Detection:
    keyword: str        # label, e.g. HEY_BUDDY
    time_s: float       # engine clock (seconds of audio consumed) when it fired


class DetectionGate:
    """Suppress repeat fires of the same keyword within `cooldown_s`.
    Mirrors com.findmyphone.wakeword.core.DetectionGate."""

    def __init__(self, cooldown_s: float = 2.0):
        self.cooldown_s = cooldown_s
        self._last: dict[str, float] = {}

    def allow(self, keyword: str, now_s: float) -> bool:
        last = self._last.get(keyword)
        if last is not None and now_s - last < self.cooldown_s:
            return False
        self._last[keyword] = now_s
        return True


@dataclass
class EngineStats:
    audio_s: float = 0.0
    kws_audio_s: float = 0.0     # audio actually pushed through the spotter
    vad_cpu_s: float = 0.0
    kws_cpu_s: float = 0.0
    activations: int = 0

    @property
    def duty_cycle(self) -> float:
        return self.kws_audio_s / self.audio_s if self.audio_s else 0.0

    @property
    def rtf(self) -> float:
        """CPU seconds per audio second (lower is better; 0.01 = 1% of one core)."""
        return (self.vad_cpu_s + self.kws_cpu_s) / self.audio_s if self.audio_s else 0.0


@dataclass
class EngineConfig:
    keywords: list[Keyword]
    gate: str = "silero"             # silero | energy | none
    vad_threshold: float = 0.4
    preroll_s: float = 0.5
    hangover_s: float = 1.0          # keep KWS running this long after speech stops
    tail_pad_s: float = 0.8          # silence fed on deactivation so a word at the very end still decodes
    cooldown_s: float = 2.0
    num_threads: int = 1
    int8: bool = True                # int8 models: ~2x faster, what the phone should use
    max_active_paths: int = 4        # 8 raises recall in noise but ~10x the false alarms; see README
    num_trailing_blanks: int = 1
    agc: bool = True                 # digital gain for distant (quiet) speech; see agc.py


class WakeWordEngine:
    def __init__(self, cfg: EngineConfig, tokenizer: UnigramTokenizer | None = None):
        import sherpa_onnx

        self.cfg = cfg
        tok = tokenizer or default_tokenizer()
        self._keywords_str = "/".join(k.to_sherpa_line(tok) for k in cfg.keywords)

        d = models.kws_dir()
        sfx = ".int8.onnx" if cfg.int8 else ".onnx"
        stem = "epoch-12-avg-2-chunk-16-left-64"
        # sherpa requires a keywords file at construction; streams get the real list.
        self._kwfile = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
        self._kwfile.write(self._keywords_str.replace("/", "\n") + "\n")
        self._kwfile.flush()
        self._spotter = sherpa_onnx.KeywordSpotter(
            tokens=str(d / "tokens.txt"),
            encoder=str(d / f"encoder-{stem}{sfx}"),
            decoder=str(d / f"decoder-{stem}{sfx}"),
            joiner=str(d / f"joiner-{stem}{sfx}"),
            keywords_file=self._kwfile.name,
            num_threads=cfg.num_threads,
            max_active_paths=cfg.max_active_paths,
            num_trailing_blanks=cfg.num_trailing_blanks,
        )
        if cfg.gate == "silero":
            self._gate = SileroGate(models.vad_model(), cfg.vad_threshold, cfg.num_threads)
        elif cfg.gate == "energy":
            self._gate = EnergyGate()
        elif cfg.gate == "none":
            self._gate = AlwaysOn()
        else:
            raise ValueError(f"unknown gate {cfg.gate!r}")

        self._agc = Agc() if cfg.agc else None
        self._det_gate = DetectionGate(cfg.cooldown_s)
        self._preroll: collections.deque[np.ndarray] = collections.deque()
        self._preroll_n = 0
        self._stream = None
        self._silence_n = 0
        self._clock_n = 0
        self.stats = EngineStats()
        self._callbacks: list[Callable[[Detection], None]] = []

    # -- public -------------------------------------------------------------
    def on_detect(self, cb: Callable[[Detection], None]) -> None:
        self._callbacks.append(cb)

    @property
    def listening(self) -> bool:
        """True while the spotter is active (someone is speaking)."""
        return self._stream is not None

    def accept(self, samples: np.ndarray) -> list[Detection]:
        """Feed float32 mono 16 kHz samples in [-1, 1]. Any chunk size."""
        samples = np.asarray(samples, dtype=np.float32)
        if self._agc is not None:
            samples = self._agc.process(samples)
        self._clock_n += len(samples)
        self.stats.audio_s += len(samples) / SAMPLE_RATE

        t0 = time.perf_counter()
        speech = self._gate.is_speech(samples)
        self.stats.vad_cpu_s += time.perf_counter() - t0

        out: list[Detection] = []
        if self._stream is None and speech:
            self._activate()
            for chunk in self._preroll:
                out += self._feed(chunk)
        if self._stream is not None:
            out += self._feed(samples)
            self._silence_n = 0 if speech else self._silence_n + len(samples)
            if self._silence_n >= self.cfg.hangover_s * SAMPLE_RATE:
                out += self._deactivate()

        self._preroll.append(samples)
        self._preroll_n += len(samples)
        while self._preroll and self._preroll_n - len(self._preroll[0]) >= self.cfg.preroll_s * SAMPLE_RATE:
            self._preroll_n -= len(self._preroll.popleft())
        return out

    def flush(self) -> list[Detection]:
        return self._deactivate() if self._stream is not None else []

    def run(self, chunks: Iterable[np.ndarray]) -> list[Detection]:
        out: list[Detection] = []
        for c in chunks:
            out += self.accept(c)
        return out + self.flush()

    # -- internals ----------------------------------------------------------
    def _activate(self) -> None:
        self._stream = self._spotter.create_stream(self._keywords_str)
        self._silence_n = 0
        self.stats.activations += 1

    def _deactivate(self) -> list[Detection]:
        out = self._feed(np.zeros(int(self.cfg.tail_pad_s * SAMPLE_RATE), dtype=np.float32), clock=False)
        self._stream = None
        return out

    def _feed(self, samples: np.ndarray, clock: bool = True) -> list[Detection]:
        t0 = time.perf_counter()
        self.stats.kws_audio_s += len(samples) / SAMPLE_RATE
        self._stream.accept_waveform(SAMPLE_RATE, samples)
        out: list[Detection] = []
        while self._spotter.is_ready(self._stream):
            self._spotter.decode_stream(self._stream)
            label = self._spotter.get_result(self._stream)
            if label:
                self._spotter.reset_stream(self._stream)
                now = self._clock_n / SAMPLE_RATE
                if self._det_gate.allow(label, now):
                    d = Detection(label, now)
                    out.append(d)
                    for cb in self._callbacks:
                        cb(d)
        self.stats.kws_cpu_s += time.perf_counter() - t0
        return out
