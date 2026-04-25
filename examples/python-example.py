#!/usr/bin/env python3
"""Example: drive btone-mod-c with the generated Python client.

Run from repo root:
    PYTHONPATH=clients/python python3 examples/python-example.py
"""
from __future__ import annotations

import base64
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "clients" / "python"))

from btone_client import BtoneClient


def main() -> int:
    bot = BtoneClient()
    print("=== python example ===")

    # 1. Self-introspect
    spec = bot.rpc_discover()
    print(f"discovered {len(spec['methods'])} methods")

    # 2. Read player state
    state = bot.player_state()
    print(json.dumps({
        "inWorld": state["inWorld"],
        "name": state.get("name"),
        "hp": state.get("health"),
        "food": state.get("food"),
        "pos": state.get("blockPos"),
    }))

    # 3. Inventory snippet
    inv = bot.player_inventory()
    print(json.dumps(inv["main"][:3]))

    # 4. Screenshot — write base64 PNG to disk
    shot = bot.world_screenshot({"width": 480, "yaw": 180, "pitch": -5})
    fd, path = tempfile.mkstemp(prefix="btone-py-shot.", suffix=".png")
    Path(path).write_bytes(base64.b64decode(shot["image"]))
    print(f"wrote {Path(path).stat().st_size} bytes to {path}")

    # 5. Low-level call() works for ad-hoc methods (e.g. discover-and-filter)
    methods = bot.call("rpc.discover")["methods"]
    baritone = [m["name"] for m in methods if m["name"].startswith("baritone.")]
    print(f"baritone.* methods: {baritone}")

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
