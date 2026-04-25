#!/usr/bin/env python3
"""Deposit one item type from inv into a target chest, slot-by-slot.

Designed for the cases where QUICK_MOVE is unreliable on double-chest
screens. Manually picks each inv-side stack, drops into the first
empty chest slot. Verifies cursor empty between steps, retries if
not, breaks if no chest space remaining.

Usage:
    python3 bin/deposit-to-chest.py <x> <y> <z> <item_id> [--max-stacks N]

Example:
    python3 bin/deposit-to-chest.py 1014 70 829 minecraft:cobblestone
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "clients" / "python"))
from btone_client import BtoneClient  # noqa: E402


def chest_inv_split(slots: list[dict]) -> int:
    """Return the slot index where inv side starts.

    Single chest: 27. Double chest: 54.
    Heuristic: max chest-side slot is < 54 → single; else double.
    Use the largest slot in the response — if any slot >= 54, it's a
    double chest (54 chest + 36 inv = up to 90).
    """
    max_slot = max((s["slot"] for s in slots), default=0)
    return 54 if max_slot >= 54 else 27


def first_empty_chest_slot(slots: list[dict], split: int, all_slot_count: int) -> int | None:
    """Find lowest-numbered empty chest slot.

    `slots` is sparse — empty slots aren't represented. So we compute
    the set of occupied chest slots and pick the lowest unoccupied
    integer in [0, split).
    """
    occupied = {s["slot"] for s in slots if s["slot"] < split}
    for i in range(split):
        if i not in occupied:
            return i
    return None


def first_partial_chest_slot(slots: list[dict], split: int, item_id: str) -> tuple[int, int] | None:
    """Return (slot, count) of an existing chest stack of item_id with room."""
    for s in slots:
        if s["slot"] < split and s.get("id") == item_id and s.get("count", 0) < 64:
            return s["slot"], s["count"]
    return None


def first_inv_stack(slots: list[dict], split: int, item_id: str) -> tuple[int, int] | None:
    for s in slots:
        if s["slot"] >= split and s.get("id") == item_id:
            return s["slot"], s["count"]
    return None


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)
    p.add_argument("z", type=int)
    p.add_argument("item_id", help="e.g. minecraft:cobblestone")
    p.add_argument("--max-stacks", type=int, default=64,
                   help="safety cap on iterations (default 64)")
    args = p.parse_args()

    bot = BtoneClient()

    # Close any stale screen, open target.
    bot.call("container.close")
    time.sleep(0.4)
    open_resp = bot.call("container.open", {"x": args.x, "y": args.y, "z": args.z})
    print(f"open {args.x},{args.y},{args.z}: {open_resp}")
    time.sleep(1.0)

    state = bot.call("container.state")
    slots = state.get("slots", [])
    if not slots:
        print("ERROR: container did not open (empty state). Bot may not be adjacent.", file=sys.stderr)
        return 2

    split = chest_inv_split(slots)
    print(f"detected {'double' if split == 54 else 'single'} chest (split={split})")

    moved_total = 0
    moved_stacks = 0
    for iteration in range(args.max_stacks):
        state = bot.call("container.state")
        slots = state.get("slots", [])

        # Find an inv-side stack of our item.
        inv = first_inv_stack(slots, split, args.item_id)
        if inv is None:
            print(f"done — no more {args.item_id} in inv side")
            break
        inv_slot, inv_count = inv

        # Prefer existing partial chest stack (better packing); else
        # first empty chest slot.
        partial = first_partial_chest_slot(slots, split, args.item_id)
        if partial is not None:
            target_slot, target_count = partial
            target_kind = f"partial({target_count})"
        else:
            empty = first_empty_chest_slot(slots, split, len(slots))
            if empty is None:
                print(f"chest full (no empty slot, no partial); stopping")
                break
            target_slot = empty
            target_kind = "empty"

        # PICKUP from inv slot, then PICKUP into target chest slot.
        bot.call("container.click", {"slot": inv_slot, "button": 0, "actionType": "PICKUP"})
        time.sleep(0.15)
        bot.call("container.click", {"slot": target_slot, "button": 0, "actionType": "PICKUP"})
        time.sleep(0.25)

        # Verify the inv slot is now empty (or partly drained for partial-target case).
        post = bot.call("container.state")
        post_slots = post.get("slots", [])
        post_inv_count = next(
            (s.get("count", 0) for s in post_slots if s["slot"] == inv_slot and s.get("id") == args.item_id),
            0,
        )
        moved = inv_count - post_inv_count
        print(f"  [{iteration:02d}] inv {inv_slot} ({inv_count}) -> chest {target_slot} ({target_kind}); moved {moved}")
        moved_total += moved
        if moved == 0:
            print("  no movement — possible state desync; retry once")
            time.sleep(0.5)
            continue
        moved_stacks += 1

    bot.call("container.close")
    print(f"\nDONE. moved {moved_total} units across {moved_stacks} stacks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
