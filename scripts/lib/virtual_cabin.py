"""A virtual car cabin for the emulator's microphone, with no sound on the computer.

The emulator's gRPC bridge gives the audio the device plays (EmulatorController.streamAudio) and
takes audio for its microphone (EmulatorController.injectAudio). This daemon joins the two the way
a car does: the music another app plays comes back to the microphone late, quieter and reflected
off the cabin, mixed with a passenger's voice from a file at set times and a little road noise.

Closing the injectAudio stream after the device has recorded from it crashes emulator 37.2.12
(SIGSEGV in my_microphone_avail, docs/05 "소리 없는 에뮬레이터 오디오"), so the daemon keeps the
stream open for as long as the emulator runs and changes only what it feeds. Control is a scene
file the daemon reads whenever it changes; status is a JSON file it rewrites every second.

Run through scripts/virtual-cabin.sh, which prepares the venv and the gRPC stubs in build/.
"""

import argparse
import glob
import hashlib
import json
import os
import queue
import signal
import subprocess
import sys
import threading
import time
import wave

RATE = 48_000
# Music queued before the cabin starts playing it into the microphone: streamAudio's bursts fit in it.
PREBUFFER = RATE * 60 // 1000
BLOCK = RATE // 50  # 20 ms
LEAD_S = 0.1  # how far ahead of real time the injector may run; the emulator buffers about 300 ms
STATE_DIR = os.environ.get("VIRTUAL_CABIN_DIR", "")
SCENE_FILE = "scene.json"
STATUS_FILE = "status.json"
PID_FILE = "daemon.pid"
LOG_FILE = "daemon.log"

DEFAULT_SCENE = {
    "music": True,
    "music_gain_db": 12.0,
    "delay_ms": 80.0,
    "reflections": True,
    "voice": None,
    "voice_gain_db": 0.0,
    "voice_at_s": 5.0,
    "voice_every_s": 0.0,
    "noise_db": -60.0,
}


def state_path(name):
    return os.path.join(STATE_DIR, name)


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def db_to_gain(db):
    return 10.0 ** (db / 20.0)


def rms_dbfs(np, x):
    if len(x) == 0:
        return None
    r = float(np.sqrt(np.mean(np.square(x, dtype=np.float64))))
    return round(20.0 * np.log10(r), 1) if r > 0 else None


# ---------------------------------------------------------------- emulator discovery


def discovery_dirs():
    home = os.path.expanduser("~")
    return [
        os.path.join(home, "Library/Caches/TemporaryItems/avd/running"),  # macOS
        os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/nonexistent"), "avd/running"),  # Linux
        os.path.join(home, ".android/avd/running"),
    ]


def find_emulator(serial):
    """Returns (grpc port, token) of the running emulator with this adb serial."""
    console = serial.removeprefix("emulator-")
    seen = []
    for d in discovery_dirs():
        for ini in glob.glob(os.path.join(d, "pid_*.ini")):
            pid = int(os.path.basename(ini)[4:-4])
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                continue  # left by an emulator that died
            except PermissionError:
                pass
            with open(ini) as f:
                kv = dict(line.rstrip("\n").split("=", 1) for line in f if "=" in line)
            seen.append(kv.get("port.serial", "?"))
            if kv.get("port.serial") != console:
                continue
            if "grpc.port" not in kv:
                sys.exit(f"virtual-cabin: {serial} runs without gRPC; start it with scripts/run-emulator.sh --silent")
            if "grpc.token" not in kv:
                sys.exit(f"virtual-cabin: {serial} has gRPC without -grpc-use-token; start it with scripts/run-emulator.sh --silent")
            return kv["grpc.port"], kv["grpc.token"]
    sys.exit(f"virtual-cabin: no running emulator {serial} (running: {', '.join(seen) or 'none'})")


# ---------------------------------------------------------------- audio helpers


def load_voice(np, path):
    """Reads any file afconvert (macOS) understands as 48 kHz mono floats; WAV works everywhere."""
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    wav = path
    if not path.lower().endswith(".wav") or not is_plain_wav(path):
        key = hashlib.sha1(f"{os.path.abspath(path)}:{os.path.getmtime(path)}".encode()).hexdigest()[:12]
        wav = state_path(f"voice-{key}.wav")
        if not os.path.exists(wav):
            subprocess.run(
                ["afconvert", "-f", "WAVE", "-d", f"LEI16@{RATE}", "-c", "1", path, wav],
                check=True, capture_output=True,
            )
    with wave.open(wav) as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path}: only 16-bit WAV is read directly")
        rate, ch = w.getframerate(), w.getnchannels()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    if rate != RATE:  # linear resampling is enough for a test voice
        n = int(len(x) * RATE / rate)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return x


def is_plain_wav(path):
    try:
        with wave.open(path) as w:
            return w.getsampwidth() == 2
    except (wave.Error, EOFError):
        return False


def cabin_response(np, reflections):
    """A synthetic impulse response: the direct path, a few early reflections off the glass and
    the seats, and a short diffuse tail (RT60 about 0.15 s), with the direct path at unit gain."""
    if not reflections:
        return np.ones(1, dtype=np.float32)
    n = int(0.2 * RATE)
    h = np.zeros(n, dtype=np.float32)
    h[0] = 1.0
    for ms, g in ((2.9, 0.55), (4.7, -0.42), (7.3, 0.33), (11.1, -0.25), (16.4, 0.18), (23.0, 0.12)):
        h[int(ms * RATE / 1000)] += g
    rng = np.random.default_rng(7)
    t = np.arange(n) / RATE
    tail = rng.standard_normal(n).astype(np.float32) * np.exp(-6.9 * t / 0.15).astype(np.float32) * 0.04
    tail[: int(0.005 * RATE)] = 0
    return h + tail


class Convolver:
    """Overlap-add FFT convolution of a stream, one block at a time."""

    def __init__(self, np, h, block):
        self.np, self.h, self.block = np, h, block
        self.size = 1
        while self.size < block + len(h) - 1:
            self.size *= 2
        self.H = np.fft.rfft(h, self.size)
        self.tail = np.zeros(self.size - block, dtype=np.float32)

    def process(self, x):
        np = self.np
        y = np.fft.irfft(np.fft.rfft(x, self.size) * self.H, self.size).astype(np.float32)
        y[: len(self.tail)] += self.tail
        self.tail = y[self.block:].copy()
        return y[: self.block]


# ---------------------------------------------------------------- the daemon


class Cabin:
    def __init__(self, np, pb, stub, md):
        self.np, self.pb, self.stub, self.md = np, pb, stub, md
        self.fmt = pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
        # Real time: the emulator takes every packet as it comes and keeps only the newest ~300 ms.
        # The default mode waits for the guest, so while nothing records the stream backs up in the
        # gRPC buffers and the microphone later plays seconds-old audio (docs/05).
        self.inject_fmt = pb.AudioFormat(
            samplingRate=RATE, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16,
            mode=pb.AudioFormat.MODE_REAL_TIME,
        )
        self.music = queue.Queue(maxsize=200)  # 20 ms-ish packets from streamAudio
        self.pending = np.zeros(0, dtype=np.float32)
        self.primed = False
        self.stop = threading.Event()
        self.error = None
        self.scene = dict(DEFAULT_SCENE)
        self.scene_mtime = None
        self.scene_started = time.monotonic()
        self.voice = None
        self.voice_error = None
        self.voice_pos = None
        self.voice_plays = 0
        self.convolver = Convolver(np, cabin_response(np, True), BLOCK)
        self.delay_line = np.zeros(0, dtype=np.float32)
        self.rng = np.random.default_rng()
        self.window = {"music_in": [], "music_out": [], "voice": [], "out": [], "backlog": []}
        self.counters = {"music_packets": 0, "music_samples": 0, "injected_blocks": 0, "underruns": 0, "dropped_samples": 0, "clipped": 0}
        self.started = time.time()
        self.block_epoch = self.started

    # -- music from the emulator

    def capture(self):
        np = self.np
        while not self.stop.is_set():
            try:
                for packet in self.stub.streamAudio(self.fmt, metadata=self.md):
                    x = np.frombuffer(packet.audio, dtype="<i2").astype(np.float32) / 32768.0
                    self.counters["music_packets"] += 1
                    self.counters["music_samples"] += len(x)
                    try:
                        self.music.put_nowait(x)
                    except queue.Full:  # nobody is draining (the injector waits on the guest): keep the newest
                        try:
                            self.music.get_nowait()
                        except queue.Empty:
                            pass
                        self.music.put_nowait(x)
                    if self.stop.is_set():
                        return
            except Exception as e:  # grpc.RpcError: the emulator went away
                self.fail(f"streamAudio ended: {describe(e)}")
                return

    def music_block(self):
        """The next 20 ms of what the device played, or silence while it plays nothing."""
        np = self.np
        parts = [self.pending]
        while True:
            try:
                parts.append(self.music.get_nowait())
            except queue.Empty:
                break
        self.pending = np.concatenate(parts)
        # streamAudio delivers in bursts, and nothing drains it while the guest is not recording;
        # more than 200 ms queued would add delay the scene did not ask for, so drop the oldest.
        if len(self.pending) > BLOCK + RATE // 5:
            self.counters["dropped_samples"] += len(self.pending) - (BLOCK + PREBUFFER)
            self.pending = self.pending[-(BLOCK + PREBUFFER):]
        # A jitter buffer (underruns now counts each time it ran dry, music stopping included): streamAudio arrives in bursts, so start (and restart after running dry)
        # only with PREBUFFER queued, and on running dry keep what is queued for later instead of
        # playing it padded with silence. Padding every short block chopped the echo into pieces
        # no echo canceller can learn (docs/05 "에코 제거 끝에서 끝까지").
        if not self.primed:
            if len(self.pending) < BLOCK + PREBUFFER:
                return np.zeros(BLOCK, dtype=np.float32)
            self.primed = True
        if len(self.pending) < BLOCK:
            self.counters["underruns"] += 1
            self.primed = False
            return np.zeros(BLOCK, dtype=np.float32)
        out, self.pending = self.pending[:BLOCK], self.pending[BLOCK:]
        self.window["backlog"].append(len(self.pending))
        return out

    # -- the scene

    def reload_scene(self):
        path = state_path(SCENE_FILE)
        try:
            mtime = os.path.getmtime(path)
        except FileNotFoundError:
            return
        if mtime == self.scene_mtime:
            return
        self.scene_mtime = mtime
        try:
            with open(path) as f:
                scene = dict(DEFAULT_SCENE, **json.load(f))
        except (OSError, ValueError) as e:
            log(f"scene unreadable, keeping the last one: {e}")
            return
        np = self.np
        if scene["reflections"] != self.scene["reflections"]:
            self.convolver = Convolver(np, cabin_response(np, scene["reflections"]), BLOCK)
        self.voice, self.voice_error = None, None
        if scene["voice"]:
            try:
                self.voice = load_voice(np, scene["voice"])
            except Exception as e:
                self.voice_error = f"{scene['voice']}: {describe(e)}"
                log(f"voice not loaded: {self.voice_error}")
        self.delay_line = np.zeros(int(scene["delay_ms"] * RATE / 1000), dtype=np.float32)
        self.scene = scene
        self.scene_started = time.monotonic()
        self.voice_pos, self.voice_plays = None, 0
        log("scene " + json.dumps(scene, ensure_ascii=False))

    def voice_block(self):
        np = self.np
        out = np.zeros(BLOCK, dtype=np.float32)
        if self.voice is None:
            return out
        s = self.scene
        elapsed = time.monotonic() - self.scene_started
        if self.voice_pos is None:
            due = s["voice_at_s"] + (s["voice_every_s"] * self.voice_plays if s["voice_every_s"] > 0 else 0)
            if elapsed >= due and (self.voice_plays == 0 or s["voice_every_s"] > 0):
                self.voice_pos = 0
                self.voice_plays += 1
                log(f"voice #{self.voice_plays} starts at {elapsed:.1f} s (due at epoch {self.block_epoch:.3f})")
        if self.voice_pos is not None:
            chunk = self.voice[self.voice_pos: self.voice_pos + BLOCK]
            out[: len(chunk)] = chunk
            self.voice_pos += BLOCK
            if self.voice_pos >= len(self.voice):
                self.voice_pos = None
        return out * db_to_gain(s["voice_gain_db"])

    # -- the microphone

    def next_block(self):
        np = self.np
        self.reload_scene()
        s = self.scene
        played = self.music_block()
        self.window["music_in"].append(played)
        mic = np.zeros(BLOCK, dtype=np.float32)
        if s["music"]:
            # The cabin: the speaker's sound reaches the microphone delay_ms late, reflected.
            line = np.concatenate((self.delay_line, played))
            delayed, self.delay_line = line[:BLOCK], line[BLOCK:]
            music = self.convolver.process(delayed) * db_to_gain(s["music_gain_db"])
            mic += music
            self.window["music_out"].append(music)
        voice = self.voice_block()
        if self.voice_pos is not None or voice.any():
            self.window["voice"].append(voice)
        mic += voice
        if s["noise_db"] is not None:
            mic += self.rng.standard_normal(BLOCK).astype(np.float32) * db_to_gain(s["noise_db"])
        clipped = int(np.count_nonzero(np.abs(mic) > 1.0))
        self.counters["clipped"] += clipped
        self.window["out"].append(mic)
        return (np.clip(mic, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()

    def packets(self):
        start = time.monotonic()
        sent = 0
        while not self.stop.is_set():
            # When this block is due at the microphone by the host clock, for the log.
            self.block_epoch = time.time() + (start + sent / RATE) - time.monotonic()
            audio = self.next_block()
            yield self.pb.AudioPacket(format=self.inject_fmt, timestamp=int(time.time() * 1e6), audio=audio)
            sent += BLOCK
            self.counters["injected_blocks"] += 1
            ahead = start + sent / RATE - LEAD_S - time.monotonic()
            if ahead > 0:
                time.sleep(ahead)
            elif ahead < -1.0:  # the guest stopped asking for audio for a while: do not catch up
                start, sent = time.monotonic(), 0
        # Never reached in normal use: ending the stream would crash the emulator (see the docstring).

    def inject(self):
        try:
            self.stub.injectAudio(self.packets(), metadata=self.md)
            self.fail("injectAudio returned")
        except Exception as e:
            self.fail(f"injectAudio ended: {describe(e)}")

    # -- status

    def fail(self, why):
        if self.error is None:
            self.error = why
            log(why)
        self.stop.set()

    def write_status(self, final=False):
        np = self.np
        # Swap first: the injector thread keeps appending to whichever window is current.
        raw, self.window = self.window, {k: [] for k in self.window}
        backlog = raw.pop("backlog")
        w = {k: (np.concatenate(v) if v else np.zeros(0)) for k, v in raw.items()}
        status = {
            "time": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "pid": os.getpid(),
            "state": "stopped" if final else "running",
            "error": self.error,
            "uptime_s": round(time.time() - self.started, 1),
            "scene": self.scene,
            "scene_age_s": round(time.monotonic() - self.scene_started, 1),
            "voice_loaded_s": round(len(self.voice) / RATE, 2) if self.voice is not None else None,
            "voice_error": self.voice_error,
            "voice_plays": self.voice_plays,
            "last_second_dbfs": {
                "device_output": rms_dbfs(np, w["music_in"]),
                "music_at_mic": rms_dbfs(np, w["music_out"]),
                "voice_at_mic": rms_dbfs(np, w["voice"]),
                "mic": rms_dbfs(np, w["out"]),
            },
            # Device output waiting to go out, on top of delay_ms: the extra delay of the music path.
            "music_backlog_ms": round(1000 * sum(backlog) / len(backlog) / RATE) if backlog else None,
            "counters": self.counters,
        }
        tmp = state_path(STATUS_FILE + ".tmp")
        with open(tmp, "w") as f:
            json.dump(status, f, indent=1)
        os.replace(tmp, state_path(STATUS_FILE))
        return status

    def run(self):
        self.reload_scene()
        threads = [threading.Thread(target=self.capture, daemon=True), threading.Thread(target=self.inject, daemon=True)]
        for t in threads:
            t.start()
        last = None
        while not self.stop.wait(1.0):
            st = self.write_status()
            line = st["last_second_dbfs"]
            summary = f"out {line['device_output']} music {line['music_at_mic']} voice {line['voice_at_mic']} mic {line['mic']}"
            if summary != last and int(st["uptime_s"]) % 10 == 0:
                log(f"dBFS {summary}")
                last = summary
        self.write_status(final=True)
        return 1 if self.error else 0


def describe(e):
    code = getattr(e, "code", None)
    details = getattr(e, "details", None)
    if callable(code) and callable(details):
        return f"{code().name} {details()}"
    return f"{type(e).__name__}: {e}"


def serve(args):
    import grpc  # noqa: imported here so the other commands work without the venv
    import numpy as np

    sys.path.insert(0, os.environ["VIRTUAL_CABIN_STUBS"])
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc

    port, token = find_emulator(args.serial)
    stub = rpc.EmulatorControllerStub(grpc.insecure_channel(f"127.0.0.1:{port}"))
    md = [("authorization", f"Bearer {token}")]
    with open(state_path(PID_FILE), "w") as f:
        f.write(str(os.getpid()))
    log(f"serving {args.serial} over gRPC 127.0.0.1:{port}: device output -> cabin -> microphone at {RATE} Hz")
    cabin = Cabin(np, pb, stub, md)

    def on_term(*_):
        # Leaving here closes the injection stream, which crashes an emulator whose microphone
        # was used. `stop` refuses while the emulator runs; a signal is the caller's choice.
        cabin.fail("terminated by signal")

    signal.signal(signal.SIGTERM, on_term)
    code = cabin.run()
    try:
        os.remove(state_path(PID_FILE))
    except FileNotFoundError:
        pass
    return code


# ---------------------------------------------------------------- the commands


def daemon_pid():
    try:
        with open(state_path(PID_FILE)) as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def read_status():
    try:
        with open(state_path(STATUS_FILE)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def start(args):
    pid = daemon_pid()
    if pid:
        print(f"virtual-cabin: already running (pid {pid})")
        return 0
    find_emulator(args.serial)  # fail here, not in the background
    if args.reset_scene or not os.path.exists(state_path(SCENE_FILE)):
        write_scene(dict(DEFAULT_SCENE, music=False, voice=None))
    try:
        os.remove(state_path(STATUS_FILE))
    except FileNotFoundError:
        pass
    logf = open(state_path(LOG_FILE), "a")
    proc = subprocess.Popen(
        [sys.executable, os.path.abspath(__file__), "--serial", args.serial, "serve"],
        stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True,
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        st = read_status()
        if st and st["counters"]["injected_blocks"] > 0:
            print(f"virtual-cabin: running (pid {proc.pid}), log {state_path(LOG_FILE)}")
            return 0
        if proc.poll() is not None:
            break
        time.sleep(0.5)
    print(f"virtual-cabin: did not start; see {state_path(LOG_FILE)}", file=sys.stderr)
    return 1


def write_scene(scene):
    tmp = state_path(SCENE_FILE + ".tmp")
    with open(tmp, "w") as f:
        json.dump(scene, f, indent=1, ensure_ascii=False)
    os.replace(tmp, state_path(SCENE_FILE))


def on_off(v):
    if v in ("on", "true", "yes", "1"):
        return True
    if v in ("off", "false", "no", "0"):
        return False
    raise argparse.ArgumentTypeError("on or off")


def noise(v):
    return None if v == "off" else float(v)


def scene(args):
    s = dict(DEFAULT_SCENE)
    if not args.reset:
        try:
            with open(state_path(SCENE_FILE)) as f:
                s.update(json.load(f))
        except (OSError, ValueError):
            pass
    for key in ("music", "music_gain_db", "delay_ms", "reflections", "voice_gain_db", "voice_at_s", "voice_every_s"):
        v = getattr(args, key)
        if v is not None:
            s[key] = v
    if args.noise_db is not None:
        s["noise_db"] = noise(args.noise_db)
    if args.voice is not None:
        s["voice"] = None if args.voice == "off" else os.path.abspath(args.voice)
        if s["voice"] and not os.path.isfile(s["voice"]):
            sys.exit(f"virtual-cabin: no voice file {s['voice']}")
    if s["delay_ms"] < 0 or s["delay_ms"] > 1000:
        sys.exit("virtual-cabin: --delay-ms must be 0..1000")
    write_scene(s)
    print("virtual-cabin: scene " + json.dumps(s, ensure_ascii=False))
    if not daemon_pid():
        print("virtual-cabin: the daemon is not running; the scene applies once it starts (virtual-cabin.sh start)", file=sys.stderr)
        return 1
    # Wait for the daemon to pick it up, so a caller can start timing right after.
    deadline = time.time() + 3
    while time.time() < deadline:
        st = read_status()
        if st and st["scene"] == s and st["scene_age_s"] < 3:
            if st.get("voice_error"):
                print(f"virtual-cabin: voice not loaded: {st['voice_error']}", file=sys.stderr)
                return 1
            return 0
        time.sleep(0.2)
    print("virtual-cabin: the daemon has not applied the scene yet", file=sys.stderr)
    return 1


def status(args):
    st = read_status()
    pid = daemon_pid()
    if not st:
        print("virtual-cabin: no status (not started)")
        return 1
    if args.json:
        print(json.dumps(st, indent=1, ensure_ascii=False))
    else:
        d = st["last_second_dbfs"]
        sc = st["scene"]
        print(f"state {st['state'] if pid else 'not running'} (pid {pid}), uptime {st['uptime_s']} s, status at {st['time']}")
        if st["error"]:
            print(f"error {st['error']}")
        print(
            f"scene music {'on' if sc['music'] else 'off'} {sc['music_gain_db']:+.1f} dB, delay {sc['delay_ms']:.0f} ms, "
            f"reflections {'on' if sc['reflections'] else 'off'}, voice {sc['voice'] or 'off'}"
            + (f" {sc['voice_gain_db']:+.1f} dB at {sc['voice_at_s']} s every {sc['voice_every_s']} s (played {st['voice_plays']})" if sc["voice"] else "")
            + f", noise {sc['noise_db'] if sc['noise_db'] is not None else 'off'}"
        )
        print(
            f"last second dBFS: device output {d['device_output']}, music at mic {d['music_at_mic']}, "
            f"voice at mic {d['voice_at_mic']}, mic {d['mic']}; music backlog {st.get('music_backlog_ms')} ms"
        )
        print("counters " + ", ".join(f"{k} {v}" for k, v in st["counters"].items()))
    return 0 if pid and not st["error"] else 1


def stop(args):
    pid = daemon_pid()
    if not pid:
        print("virtual-cabin: not running")
        return 0
    if not args.force:
        try:
            find_emulator_quiet(args.serial)
            print(
                "virtual-cabin: refusing to stop while the emulator runs: closing the microphone stream crashes "
                "emulator 37.2.12 once the device has recorded (docs/05). Quiet it with "
                "'virtual-cabin.sh scene --music off --voice off --noise off', or stop the emulator first "
                "(adb emu kill); the daemon then exits by itself. --force stops it anyway.",
                file=sys.stderr,
            )
            return 1
        except SystemExit:
            pass
    os.kill(pid, signal.SIGTERM)
    for _ in range(20):
        if not daemon_pid():
            break
        time.sleep(0.25)
    print(f"virtual-cabin: stopped (pid {pid})")
    return 0


def find_emulator_quiet(serial):
    import contextlib
    import io

    with contextlib.redirect_stderr(io.StringIO()):
        return find_emulator(serial)


def main():
    global STATE_DIR
    p = argparse.ArgumentParser(prog="virtual-cabin.sh", description=__doc__.split("\n\n")[0])
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    sub = p.add_subparsers(dest="cmd", required=True)
    sp = sub.add_parser("start", help="start the daemon in the background (quiet scene: noise only)")
    sp.add_argument("--reset-scene", action="store_true", help="start from the quiet scene even if one is saved")
    sub.add_parser("serve", help="run the daemon in the foreground")
    sc = sub.add_parser("scene", help="change what the microphone hears; unspecified settings keep their value")
    sc.add_argument("--reset", action="store_true", help="start from the defaults instead of the current scene")
    sc.add_argument("--music", type=on_off, help="on|off: the device's own output, back through the cabin")
    sc.add_argument("--music-gain-db", type=float, help=f"gain on that music (default {DEFAULT_SCENE['music_gain_db']})")
    sc.add_argument("--delay-ms", type=float, help=f"speaker-to-microphone delay (default {DEFAULT_SCENE['delay_ms']:.0f})")
    sc.add_argument("--reflections", type=on_off, help="on|off: the synthetic cabin echo (default on)")
    sc.add_argument("--voice", help="a voice file (wav, aiff, m4a…) or off")
    sc.add_argument("--voice-gain-db", type=float, help="gain on the voice (default 0)")
    sc.add_argument("--voice-at-s", type=float, help="first voice this many seconds after the scene (default 5)")
    sc.add_argument("--voice-every-s", type=float, help="repeat the voice this often, 0 = once (default 0)")
    sc.add_argument("--noise-db", help=f"road noise level in dBFS or off (default {DEFAULT_SCENE['noise_db']})")
    st = sub.add_parser("status", help="levels of the last second and counters")
    st.add_argument("--json", action="store_true")
    so = sub.add_parser("stop", help="stop the daemon (refused while the emulator runs)")
    so.add_argument("--force", action="store_true", help="stop even if that crashes the emulator")
    args = p.parse_args()
    STATE_DIR = os.environ.get("VIRTUAL_CABIN_DIR") or sys.exit("virtual-cabin: run it through scripts/virtual-cabin.sh")
    os.makedirs(STATE_DIR, exist_ok=True)
    return {"start": start, "serve": serve, "scene": scene, "status": status, "stop": stop}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
