#!/usr/bin/env python3
"""Synthesise the FLASH BOOSTER sound packs.

Three packs are rendered here (NEON, SOFT, ARCADE); the fourth, SILENT, is the absence of
sound and lives only in the UI. Each pack has four interface effects plus one 50 second
ambient loop that plays during a boost session.

Everything is generated from scratch with numpy and written as 16-bit 44.1 kHz stereo PCM,
which Android's SoundPool and MediaPlayer read without any extra library. Ambient loops use
frequencies that complete a whole number of cycles inside the loop, so they repeat without a
click.

    python3 tools/make_sounds.py

Output: app/src/main/res/raw/{sfx_<effect>_<pack>,ambient_<pack>}.wav
"""

import os
import wave

import numpy as np

RATE = 44100
AMBIENT_SECONDS = 50
OUT = os.path.join("app", "src", "main", "res", "raw")


def t_axis(seconds):
    return np.arange(int(RATE * seconds), dtype=np.float64) / RATE


def env_ad(seconds, attack, decay, curve=2.0):
    """Attack / decay envelope, both in seconds."""
    t = t_axis(seconds)
    attack_n = max(int(attack * RATE), 1)
    decay_n = max(len(t) - attack_n, 1)
    up = np.minimum(np.arange(len(t)) / attack_n, 1.0)
    down = np.maximum(1.0 - (np.arange(len(t)) - attack_n) / decay_n, 0.0) ** curve
    return up * down


def sine(freq, t):
    return np.sin(2.0 * np.pi * freq * t)


def bell(freq, seconds, detune=1.0):
    t = t_axis(seconds)
    partials = [(1.0, 1.0), (2.01, 0.35), (3.02, 0.16), (4.98, 0.07)]
    body = sum(amp * sine(freq * mult * detune, t) for mult, amp in partials)
    return body * env_ad(seconds, 0.004, seconds * 0.9, 2.6)


def blip(freq, seconds, shape="sine", sweep=0.0):
    t = t_axis(seconds)
    glide = freq * (1.0 + sweep * (t / max(seconds, 1e-6)))
    phase = 2.0 * np.pi * np.cumsum(glide) / RATE
    wave_shape = np.sin(phase) if shape == "sine" else np.sign(np.sin(phase)) * 0.7
    return wave_shape * env_ad(seconds, 0.002, seconds * 0.85, 2.2)


def noise_hit(seconds, brightness=0.5):
    rng = np.random.default_rng(11)
    t = t_axis(seconds)
    raw = rng.normal(0.0, 1.0, len(t))
    smooth = np.convolve(raw, np.ones(max(int(brightness * 60), 1)) / 60, mode="same")
    return smooth * env_ad(seconds, 0.001, seconds * 0.7, 3.0)


def normalise(signal, peak=0.85):
    top = float(np.max(np.abs(signal))) or 1.0
    return signal * (peak / top)


def stereo(mono, width=0.0):
    """Return interleaved stereo; width adds a tiny haas offset for a wider image."""
    if width <= 0.0:
        return np.stack([mono, mono], axis=1)
    delay = max(int(width * RATE), 1)
    right = np.concatenate([np.zeros(delay), mono[:-delay]])
    return np.stack([mono, right], axis=1)


def write_wav(path, samples):
    data = np.clip(samples, -1.0, 1.0)
    pcm = (data * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(RATE)
        handle.writeframes(pcm.tobytes())
    print(f"{path}  {os.path.getsize(path) / 1048576:.2f} MB")


# --------------------------------------------------------------------------- effects

def pack_neon():
    return {
        "tap": normalise(blip(1320, 0.11, "sine", -0.35)),
        "success": normalise(
            np.concatenate([bell(880, 0.28), bell(1320, 0.45) * 0.8]), 0.8
        ),
        "boost": normalise(
            np.concatenate([
                blip(440, 0.25, "sine", 1.2) * 0.6,
                blip(660, 0.3, "sine", 1.2) * 0.7,
                bell(990, 0.7) * 0.9,
            ]),
            0.85,
        ),
        "error": normalise(np.concatenate([blip(220, 0.18, "sine", -0.5), blip(165, 0.3)]), 0.8),
    }


def pack_soft():
    return {
        "tap": normalise(blip(660, 0.14, "sine", -0.1) * 0.7),
        "success": normalise(np.concatenate([bell(523, 0.5, 1.0), bell(784, 0.6) * 0.6]), 0.55),
        "boost": normalise(
            np.concatenate([bell(392, 0.6) * 0.6, bell(587, 0.8) * 0.5, bell(784, 1.0) * 0.4]),
            0.55,
        ),
        "error": normalise(blip(262, 0.4, "sine", -0.2) * 0.6, 0.5),
    }


def pack_arcade():
    return {
        "tap": normalise(blip(1046, 0.06, "square", 0.2), 0.6),
        "success": normalise(
            np.concatenate([blip(f, 0.08, "square") for f in (784, 988, 1174, 1568)]), 0.7
        ),
        "boost": normalise(
            np.concatenate([
                blip(f, 0.07, "square") for f in (392, 523, 659, 784, 1046, 1318)
            ] + [blip(1568, 0.35, "square", 0.4)]),
            0.8,
        ),
        "error": normalise(
            np.concatenate([blip(330, 0.1, "square"), noise_hit(0.12, 0.2)]), 0.7
        ),
    }


# --------------------------------------------------------------------------- ambience

def quantise(freq):
    """Snap a frequency so the loop ends exactly where it started."""
    cycles = max(round(freq * AMBIENT_SECONDS), 1)
    return cycles / AMBIENT_SECONDS


def pad(freqs, seconds, detune=0.0, phase=0.0):
    t = t_axis(seconds)
    out = np.zeros_like(t)
    for freq in freqs:
        f = quantise(freq + detune)
        out += sine(f, t + phase) + 0.35 * sine(f * 2.0, t)
    return out / (len(freqs) * 1.35)


def ambient_neon():
    seconds = AMBIENT_SECONDS
    chord_a = [110.0, 164.8, 220.0, 261.6]      # Am7
    chord_b = [98.0, 146.8, 196.0, 246.9]       # G
    left = pad(chord_a, seconds) * 0.5 + pad(chord_b, seconds) * 0.5
    right = pad(chord_a, seconds, detune=0.0, phase=0.7) * 0.5 + pad(
        chord_b, seconds, detune=0.0, phase=0.4
    ) * 0.5
    # Slow shimmer every 5 seconds, decaying well before the loop restarts.
    line = np.zeros(int(RATE * seconds))
    for index, freq in enumerate([880.0, 1046.5, 1174.7, 1318.5]):
        start = int(index * 5.0 * RATE)
        tone = bell(freq, 4.0, 1.0) * 0.18
        line[start:start + len(tone)] += tone
    swell = 0.5 + 0.5 * np.sin(2.0 * np.pi * quantise(0.1) * t_axis(seconds))
    mix = np.stack([left * swell + line, right * swell + line], axis=1)
    return normalise(mix, 0.55)


def ambient_soft():
    seconds = AMBIENT_SECONDS
    chords = [
        (0, 25, [174.6, 261.6, 349.2]),          # F
        (25, 50, [196.0, 261.6, 392.0]),         # C
    ]
    mix = np.zeros((int(RATE * seconds), 2))
    for start, end, freqs in chords:
        span = end - start
        tone_l = pad(freqs, span, phase=0.0)
        tone_r = pad(freqs, span, phase=0.9)
        window = np.sin(np.linspace(0.0, np.pi, int(RATE * span))) ** 0.6
        block = np.stack([tone_l * window, tone_r * window], axis=1) * 0.5
        mix[int(start * RATE):int(start * RATE) + len(block)] += block
    return normalise(mix, 0.45)


def ambient_arcade():
    seconds = AMBIENT_SECONDS
    t = t_axis(seconds)
    base = np.zeros_like(t)
    notes = [130.8, 164.8, 196.0, 261.6]
    step = 0.5
    index = 0
    position = 0.0
    while position + step < seconds:
        freq = quantise(notes[index % len(notes)])
        start = int(position * RATE)
        span = int(step * RATE)
        segment = np.sign(np.sin(2.0 * np.pi * freq * t[:span])) * 0.16
        base[start:start + span] += segment * np.linspace(1.0, 0.35, span)
        index += 1
        position += step
    pad_tone = pad([65.4, 98.0, 130.8], seconds) * 0.35
    mix = np.stack([base + pad_tone, np.roll(base, 220) + pad_tone], axis=1)
    return normalise(mix, 0.5)


PACKS = {
    "neon": (pack_neon, ambient_neon),
    "soft": (pack_soft, ambient_soft),
    "arcade": (pack_arcade, ambient_arcade),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name, (effects, ambient) in PACKS.items():
        for effect, samples in effects().items():
            path = os.path.join(OUT, f"sfx_{effect}_{name}.wav")
            write_wav(path, stereo(samples, width=0.0006))
        amp = ambient()
        path = os.path.join(OUT, f"ambient_{name}.wav")
        write_wav(path, amp)
    for entry in sorted(os.listdir(OUT)):
        if entry.endswith(".wav"):
            total += os.path.getsize(os.path.join(OUT, entry))
    print(f"audio total: {total / 1048576:.2f} MB")


if __name__ == "__main__":
    main()
