"""Builds cabin scenes with known answers for the score (docs/09 "흥 점수 v2"; test material only).

Usage: python mix.py <work> <song>...  with <work>/wav/<song>.wav and Demucs's two stems of it in
<work>/sep/htdemucs/<song>/ (vocals.wav, no_vocals.wav). Each song plays through the car's
speakers (a colored, reverberant path). A passenger "sings along" with the song's own separated
vocals, a little late and with a wandering pitch, close to the microphone. Writes 48 kHz mono
16-bit WAVs to <work>/clips with a JSON of which seconds the passenger sang, belted, talked or
cheered. Talk is made with macOS `say` (the Korean voice Yuna).
"""
import json
import os
import subprocess
import sys
import zlib

import numpy as np
import soundfile as sf
from scipy import signal

RATE = 48_000
ROOT = ""
rng = np.random.default_rng(7)


def load(path):
    x, rate = sf.read(path, always_2d=True)
    x = x.mean(axis=1)
    if rate != RATE:
        g = np.gcd(rate, RATE)
        x = signal.resample_poly(x, RATE // g, rate // g)
    return x.astype(np.float64)


def rms(x):
    return np.sqrt(np.mean(x * x) + 1e-20)


def db(x):
    return 20 * np.log10(rms(x))


def room(rt60, direct=1.0, wet=0.3):
    """A reverberant impulse response: the direct sound and a decaying noise tail."""
    n = int(rt60 * RATE)
    t = np.arange(n) / RATE
    tail = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    tail /= np.sqrt(np.sum(tail * tail))
    ir = wet * tail
    ir[0] += direct
    return ir


# One cabin for every scene and song, so scenes differ only in the passenger.
SPEAKER_ROOM = room(rt60=0.15, direct=1.0, wet=0.5)
SEAT_ROOM = room(rt60=0.08, direct=1.0, wet=0.15)


def reverb(x, ir):
    return signal.fftconvolve(x, ir)[: len(x)]


def speakers(x):
    b, a = signal.butter(2, [80, 9_000], btype="band", fs=RATE)
    return reverb(signal.lfilter(b, a, x), SPEAKER_ROOM)


def passenger(vocals):
    """The song's vocals as a passenger singing along: about 0.1 s late, the pitch wandering by up
    to about 60 cents as the delay drifts, a slight timing wobble, and the cabin's short reverb."""
    n = len(vocals)
    t = np.arange(n) / RATE
    delay = 0.10 + 0.012 * np.sin(2 * np.pi * 0.21 * t) + 0.006 * np.sin(2 * np.pi * 0.53 * t + 1.0)
    src = t - delay
    out = np.interp(src, t, vocals, left=0.0, right=0.0)
    b, a = signal.butter(2, [120, 7_000], btype="band", fs=RATE)
    return reverb(signal.lfilter(b, a, out), SEAT_ROOM)


def cheer(seconds=1.4):
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for f0 in (196.0, 233.1, 277.2, 311.1, 370.0):
        f = f0 * (1 + 0.03 * np.sin(2 * np.pi * (4 + rng.random()) * t))
        phase = 2 * np.pi * np.cumsum(f) / RATE
        for h in range(1, 12):
            out += np.sin(h * phase + rng.random() * 6) / h
    out /= rms(out)
    b, a = signal.butter(2, [300, 5_000], btype="band", fs=RATE)
    breath = signal.lfilter(b, a, rng.standard_normal(n))
    out = out + 1.2 * breath / rms(breath)
    env = np.minimum(1, t / 0.08) * np.minimum(1, (seconds - t) / 0.4)
    return out * env


def talk_lines():
    lines = [
        "아 이 노래 진짜 오랜만이다",
        "다음 주에 바다 보러 갈까",
        "배고프다 휴게소 들르자",
        "이거 누구 노래였지",
        "창문 조금만 열어 줄래",
        "어제 그 영화 봤어? 진짜 재밌더라",
    ]
    out = []
    for i, line in enumerate(lines):
        aiff = os.path.join(ROOT, f"talk{i}.aiff")
        if not os.path.exists(aiff):
            subprocess.run(["say", "-v", "Yuna", "-r", "190" if i % 2 else "170", "-o", aiff, line], check=True)
        out.append(load(aiff))
    return out


def activity(vocals):
    """Per second, whether the song's singer sings (the separated vocals within 25 dB of their loudest seconds)."""
    secs = len(vocals) // RATE
    levels = np.array([db(vocals[s * RATE:(s + 1) * RATE]) for s in range(secs)])
    return levels > np.percentile(levels, 95) - 25, levels


def write(name, x, labels):
    peak = np.max(np.abs(x))
    if peak > 0.99:
        x = x * 0.99 / peak
    sf.write(os.path.join(ROOT, "clips", name + ".wav"), x, RATE, subtype="PCM_16")
    labels["file"] = name + ".wav"
    with open(os.path.join(ROOT, "clips", name + ".json"), "w") as f:
        json.dump(labels, f)


def main(songs):
    os.makedirs(os.path.join(ROOT, "clips"), exist_ok=True)
    talks = talk_lines()
    global rng
    for song in songs:
        # Each song's noise, cheers and talk depend on the song alone, not on the songs before it.
        rng = np.random.default_rng(zlib.crc32(song.encode()))
        base = os.path.join(ROOT, "sep", "htdemucs", song)
        mix = load(os.path.join(ROOT, "wav", song + ".wav"))
        vocals = load(os.path.join(base, "vocals.wav"))
        mr = load(os.path.join(base, "no_vocals.wav"))
        n = min(len(mix), len(vocals), len(mr))
        mix, vocals, mr = mix[:n], vocals[:n], mr[:n]
        secs = n // RATE
        sings, vocal_db = activity(vocals)

        # The music at the microphone near -40 dBFS, as a parked car's radio recording measured.
        heard_mix = speakers(mix)
        gain = 10 ** (-40 / 20) / rms(heard_mix)
        heard_mix = heard_mix * gain
        heard_mr = speakers(mr) * gain
        heard_vocals = speakers(vocals) * gain
        # The recorded singer's level at the microphone while singing: the reference for the passenger.
        sung = np.repeat(sings, RATE)[:n] if secs * RATE >= n else np.concatenate([np.repeat(sings, RATE), np.zeros(n - secs * RATE, bool)])
        singer_level = rms(heard_vocals[sung[: len(heard_vocals)]])
        guest = passenger(vocals)
        guest *= singer_level / rms(guest[sung[: len(guest)]])
        b, a = signal.butter(1, 200, btype="low", fs=RATE)
        noise = signal.lfilter(b, a, rng.standard_normal(n))
        noise *= 10 ** (-62 / 20) / rms(noise)

        def scene(music, parts):
            return music + noise + parts

        def mask(seconds_on):
            m = np.zeros(n)
            for s in seconds_on:
                m[s * RATE:(s + 1) * RATE] = 1.0
            # Soft edges, 50 ms.
            k = np.hanning(int(0.1 * RATE))
            return np.minimum(1, signal.fftconvolve(m, k / k.sum(), mode="same"))

        every = [s for s in range(secs) if sings[s]]
        halves = [s for s in every if (s // 20) % 2 == 0]
        chorus = [s for s in every if vocal_db[s] >= np.percentile(vocal_db[sings], 75)]

        def g(db_):
            return 10 ** (db_ / 20)

        write(f"{song}-music", scene(heard_mix, 0), {"song": song, "sang": [], "belted": [], "cheers": []})
        write(f"{song}-mr", scene(heard_mr, 0), {"song": song, "sang": [], "belted": [], "cheers": []})
        write(f"{song}-along", scene(heard_mix, guest * mask(every)), {"song": song, "sang": every, "belted": [], "cheers": []})
        write(f"{song}-half", scene(heard_mix, guest * mask(halves)), {"song": song, "sang": halves, "belted": [], "cheers": []})
        write(f"{song}-shy", scene(heard_mix, g(-6) * guest * mask(every)), {"song": song, "sang": every, "belted": [], "cheers": []})
        loud = g(3) * guest * mask(every) + (g(10) - g(3)) * guest * mask(chorus)
        cheer_parts = np.zeros(n)
        cheer_at = []
        for s in range(15, secs - 5, 40):
            c = cheer() * 10 ** (-28 / 20)
            cheer_parts[s * RATE:s * RATE + len(c)] += c[: max(0, n - s * RATE)]
            cheer_at.append(s)
        write(f"{song}-loud", scene(heard_mix, loud + cheer_parts), {"song": song, "sang": every, "belted": chorus, "cheers": cheer_at})
        write(f"{song}-mrsing", scene(heard_mr, guest * mask(every)), {"song": song, "sang": every, "belted": [], "cheers": []})
        # Belting the whole way: louder than the recording in every sung second, no cheers.
        write(f"{song}-belt", scene(heard_mix, g(9) * guest * mask(every)), {"song": song, "sang": every, "belted": every, "cheers": []})
        write(f"{song}-mrbelt", scene(heard_mr, g(9) * guest * mask(every)), {"song": song, "sang": every, "belted": every, "cheers": []})
        talk = np.zeros(n)
        talked = []
        s = 8
        while s < secs - 6:
            line = talks[rng.integers(len(talks))] * singer_level / rms(talks[0]) * g(3)
            end = min(n, s * RATE + len(line))
            talk[s * RATE:end] += line[: end - s * RATE]
            talked += list(range(s, s + int(np.ceil(len(line) / RATE))))
            s += int(len(line) / RATE) + int(rng.integers(4, 12))
        write(f"{song}-talk", scene(heard_mix, talk), {"song": song, "sang": [], "talked": talked, "belted": [], "cheers": []})
        print(song, secs, "s; singer sings", len(every), "s; chorus", len(chorus), "s")


if __name__ == "__main__":
    ROOT = os.path.abspath(sys.argv[1])
    main(sys.argv[2:])
