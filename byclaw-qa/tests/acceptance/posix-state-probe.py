"""Supplementary real-filesystem gate for the DSH state mount; no model simulation."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys


def sync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def probe(root: Path) -> dict[str, object]:
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError("Provide an existing, absolute, non-symlink isolated mount root")
    directory = root / "posix-probe"
    directory.mkdir(mode=0o700)
    sync_directory(root)

    temporary = directory / "intent.tmp"
    final = directory / "intent.json"
    with temporary.open("xb") as writer:
        writer.write(b'{"requestId":"posix-proof-01","state":"accepted"}\n')
        writer.flush()
        os.fsync(writer.fileno())
    temporary.rename(final)
    sync_directory(directory)
    assert final.read_bytes() == b'{"requestId":"posix-proof-01","state":"accepted"}\n'

    linked = directory / "intent.link"
    os.link(final, linked)
    assert final.stat().st_ino == linked.stat().st_ino
    assert linked.read_bytes() == b'{"requestId":"posix-proof-01","state":"accepted"}\n'
    sync_directory(directory)

    with (directory / "writer.lock").open("xb") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        competing = subprocess.run(
            [sys.executable, "-c", """
import fcntl, sys
with open(sys.argv[1], 'rb') as reader:
    try:
        fcntl.flock(reader, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit(23)
raise SystemExit(24)
""", str(directory / "writer.lock")],
            check=False, capture_output=True, timeout=10,
        )
        assert competing.returncode == 23, "A separate process must observe the held writer lock"

    database = directory / "state.sqlite"
    connection = sqlite3.connect(database)
    try:
        assert connection.execute("PRAGMA journal_mode=WAL").fetchone() == ("wal",)
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("CREATE TABLE request (id TEXT PRIMARY KEY, state TEXT NOT NULL)")
        connection.execute("INSERT INTO request VALUES (?, ?)", ("wal-proof-01", "accepted"))
        connection.commit()
        reader = sqlite3.connect(database)
        try:
            assert reader.execute("SELECT id, state FROM request").fetchall() == [("wal-proof-01", "accepted")]
        finally:
            reader.close()
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    finally:
        connection.close()
    sync_directory(directory)

    crash_log = directory / "crash.jsonl"
    writer = subprocess.Popen(
        [sys.executable, "-c", """
import os, sys, time
with open(sys.argv[1], 'xb') as writer:
    writer.write(b'{"requestId":"crash-proof-01","state":"accepted"}\\n')
    writer.flush()
    os.fsync(writer.fileno())
    parent = os.open(os.path.dirname(sys.argv[1]), os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(parent)
    finally:
        os.close(parent)
    print('DURABLE', flush=True)
    time.sleep(30)
""", str(crash_log)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    try:
        assert writer.stdout is not None
        assert writer.stdout.readline().strip() == "DURABLE"
        writer.send_signal(signal.SIGKILL)
        assert writer.wait(timeout=10) == -signal.SIGKILL
    finally:
        if writer.poll() is None:
            writer.kill()
            writer.wait(timeout=10)
        if writer.stdout is not None:
            writer.stdout.close()
        if writer.stderr is not None:
            writer.stderr.close()
    assert crash_log.read_bytes() == b'{"requestId":"crash-proof-01","state":"accepted"}\n'

    return {"fileSync": True, "directorySync": True, "rename": True, "hardlink": True,
            "separateProcessLock": True, "sqliteWal": True, "flushedInputAfterProcessKill": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    arguments = parser.parse_args()
    print(json.dumps(probe(arguments.root), sort_keys=True))
