import numpy as np

from wakeword.agc import Agc, amp_to_db, db_to_amp


def tone(db, n=1600):
    return (db_to_amp(db + 3.01) * np.sin(2 * np.pi * 300 * np.arange(n) / 16000)).astype(np.float32)


def rms_db(x):
    return amp_to_db(float(np.sqrt(np.mean(x.astype(np.float64) ** 2))))


def test_quiet_speech_raised_to_target():
    agc = Agc()
    for _ in range(200):  # 20 s: the level tracker releases over seconds
        out = agc.process(tone(-48))
    assert abs(rms_db(out) - -22) < 0.5
    assert abs(agc.gain_db - 26) < 0.5


def test_gain_capped_and_never_attenuates():
    agc = Agc()
    for _ in range(100):
        agc.process(tone(-80))
    assert abs(agc.gain_db - 30) < 0.1
    loud = Agc()
    for _ in range(10):
        loud.process(tone(-6))
    assert abs(loud.gain_db) < 0.01


def test_loud_onset_after_silence_not_clipped():
    agc = Agc()
    for _ in range(50):
        agc.process(np.zeros(1600, np.float32))
    out = agc.process(tone(-12))
    assert np.abs(out).max() < 0.99
    assert abs(rms_db(out) - -12) < 0.5
