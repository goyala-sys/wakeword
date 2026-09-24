"""End-to-end: fixed TTS recordings (tests/fixtures) through the real models.
Models are downloaded on first run; see tools/make_test_fixtures.py for the audio."""
from pathlib import Path

import numpy as np
import pytest

from wakeword.engine import DetectionGate, EngineConfig, WakeWordEngine
from wakeword.keywords import Keyword
from wakeword.sources import chunks, load_wav

FIX = Path(__file__).parent / "fixtures"

SR = 16000


def sil(s):
    return np.zeros(int(s * SR), np.float32)


def test_detection_gate_cooldown():
    g = DetectionGate(2.0)
    assert g.allow("A", 10.0)
    assert not g.allow("A", 11.9)
    assert g.allow("B", 11.9)
    assert g.allow("A", 12.0)


def wav(name):
    return load_wav(str(FIX / f"{name}.wav"))


def make(gate="silero", **kw):
    return WakeWordEngine(EngineConfig([Keyword("hey buddy")], gate=gate, **kw))


@pytest.mark.parametrize("name", ["hey_buddy_a", "hey_buddy_b", "hey_buddy_c"])
def test_detects_keyword_once(name):
    x = np.concatenate([sil(1), wav(name), sil(1.5)])
    dets = make().run(chunks(x))
    assert [d.keyword for d in dets] == ["HEY_BUDDY"]
    kw_end = 1 + (len(x) / SR - 2.5)
    assert 0 <= dets[0].time_s - kw_end < 1.5


def test_ignores_unrelated_and_near_miss_speech():
    x = np.concatenate([sil(0.5), wav("neg_my_buddy"), sil(1), wav("neg_hey_there"), sil(1)])
    assert make().run(chunks(x)) == []


def test_keyword_at_very_end_of_speech_is_flushed():
    # keyword followed by only ~0.2 s before the stream ends: tail padding must decode it
    x = np.concatenate([sil(1), wav("hey_buddy_a"), sil(0.2)])
    assert len(make().run(chunks(x))) == 1


def test_preroll_catches_word_onset():
    # word starts right at the first loud chunk; if pre-roll were missing, the
    # first syllable would be lost while the VAD decides
    x = np.concatenate([sil(2), wav("hey_buddy_b"), sil(1.5)])
    assert len(make().run(chunks(x, 3200))) == 1


def test_gate_idles_spotter_in_silence():
    eng = make()
    eng.run(chunks(sil(20)))
    assert eng.stats.kws_audio_s == 0
    assert eng.stats.activations == 0


def test_repeat_within_cooldown_fires_once():
    a = wav("hey_buddy_c")
    x = np.concatenate([sil(1), a, sil(1.0), a, sil(1.5)])
    assert len(make(cooldown_s=5).run(chunks(x))) == 1
    assert len(make(cooldown_s=0.1).run(chunks(x))) == 2


def test_multiple_keywords():
    eng = WakeWordEngine(EngineConfig([Keyword("hey buddy"), Keyword("find my phone")]))
    x = np.concatenate([sil(1), wav("find_my_phone"), sil(1.5), wav("hey_buddy_a"), sil(1.5)])
    assert [d.keyword for d in eng.run(chunks(x))] == ["FIND_MY_PHONE", "HEY_BUDDY"]


@pytest.mark.parametrize("name", ["hey_buddy_a", "hey_buddy_b"])
def test_agc_recovers_quiet_speech(name):
    # ~-50 dBFS: a normal voice several metres from the phone
    x = np.concatenate([sil(1), wav(name) * 10 ** (-32 / 20), sil(1.5)]).astype(np.float32)
    assert [d.keyword for d in make().run(chunks(x))] == ["HEY_BUDDY"]


def test_agc_does_not_make_near_misses_fire():
    x = np.concatenate([sil(0.5), wav("neg_my_buddy"), sil(1), wav("neg_hey_there"), sil(1)])
    assert make().run(chunks(x * 10 ** (-32 / 20))) == []
