#!/usr/bin/env python3
"""twitch-streamd — daemon that holds the Twitch stream key and manages
an ffmpeg subprocess streaming the headless Xorg display.

Listens on /run/twitch-streamd.sock (mode 0660 streamd:streamcontrol).
Line-based protocol, one client at a time:

    START    -> "ok started pid=N" | "ok already-running pid=N" | "err no-stream-key"
    STOP     -> "ok stopped" | "ok already-stopped"
    STATUS   -> "running pid=N uptime=Ns" | "stopped"

The STREAM_KEY env var is the only thing that ever holds the key. The
daemon never echoes it back over the socket. ffmpeg inherits it via
the RTMP URL constructed inside this process.
"""

import grp
import os
import socket
import subprocess
import sys
import threading
import time

SOCKET_PATH = "/run/twitch-streamd/sock"
DISPLAY = os.environ.get("DISPLAY", ":99")
XAUTHORITY = os.environ.get("XAUTHORITY", "/var/lib/twitch-streamd/.Xauthority")

_lock = threading.Lock()
_proc: "subprocess.Popen | None" = None
_started_at: float = 0.0


def _ffmpeg_argv(stream_key: str) -> "list[str]":
    return [
        "/usr/bin/ffmpeg",
        "-nostdin", "-loglevel", "warning", "-hide_banner",
        "-f", "x11grab", "-framerate", "30", "-video_size", "1280x720",
        "-i", DISPLAY,
        "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
        "-c:v", "h264_nvenc", "-preset", "p4", "-tune", "ll",
        "-b:v", "3500k", "-maxrate", "3500k", "-bufsize", "7000k",
        "-g", "60", "-keyint_min", "60",
        "-c:a", "aac", "-b:a", "160k", "-ar", "44100",
        "-f", "flv", f"rtmp://live.twitch.tv/app/{stream_key}",
    ]


def _alive() -> bool:
    return _proc is not None and _proc.poll() is None


def _spawn() -> str:
    global _proc, _started_at
    key = os.environ.get("STREAM_KEY", "").strip()
    if not key:
        return "err no-stream-key"
    _proc = subprocess.Popen(
        _ffmpeg_argv(key),
        env={
            "DISPLAY": DISPLAY,
            "XAUTHORITY": XAUTHORITY,
            "PATH": "/usr/bin:/bin",
        },
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    _started_at = time.time()
    return f"ok started pid={_proc.pid}"


def cmd_start() -> str:
    with _lock:
        if _alive():
            return f"ok already-running pid={_proc.pid}"
        return _spawn()


def cmd_stop() -> str:
    global _proc
    with _lock:
        if not _alive():
            return "ok already-stopped"
        _proc.terminate()
        try:
            _proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            _proc.kill()
            _proc.wait()
        _proc = None
        return "ok stopped"


def cmd_status() -> str:
    with _lock:
        if not _alive():
            return "stopped"
        uptime = int(time.time() - _started_at)
        return f"running pid={_proc.pid} uptime={uptime}s"


HANDLERS = {"START": cmd_start, "STOP": cmd_stop, "STATUS": cmd_status}


def serve() -> None:
    if os.path.exists(SOCKET_PATH):
        os.unlink(SOCKET_PATH)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.bind(SOCKET_PATH)
    try:
        gid = grp.getgrnam("streamcontrol").gr_gid
        os.chown(SOCKET_PATH, os.getuid(), gid)
    except KeyError:
        pass
    os.chmod(SOCKET_PATH, 0o660)
    sock.listen(4)
    print(f"twitch-streamd listening on {SOCKET_PATH}", file=sys.stderr, flush=True)
    while True:
        client, _ = sock.accept()
        try:
            with client.makefile("rwb", buffering=0) as f:
                line = f.readline().decode("ascii", errors="replace").strip().upper()
                handler = HANDLERS.get(line)
                reply = handler() if handler else f"err unknown-command:{line!r}"
                f.write((reply + "\n").encode("ascii"))
        except Exception as e:
            print(f"client error: {e}", file=sys.stderr, flush=True)
        finally:
            client.close()


if __name__ == "__main__":
    serve()
