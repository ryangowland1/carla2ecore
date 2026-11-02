#!/usr/bin/env python3
"""Streams a running CARLA scene as JSON update messages over ZeroMQ.

Connects to a CARLA server as an extra client that never ticks the world or
changes its settings, so it runs next to whatever drives the scenario
(ScenarioRunner, the CARLA Scenario Editor, the Traffic Manager, or a
script). Every --interval seconds it reads the vehicles and, once, the road
network, and publishes only the nodes that changed since the last message.
The list of vehicles is refreshed every 30 messages, so a new vehicle appears
within 30 intervals.

Node fields come from Node.to_dict() in scene_export.py: id, type, x, y,
heading, length, width, speed (vehicles only), parent-id, parent-ref. The
Eclipse plugin's ZmqSubscriber receives the messages and JsonModelUpdater
applies them to an EMF model.

With --out-dir, each message's changes also go to events.jsonl and
current_state.json in that directory, for debugging.

Typical usage example:

  python publisher.py --zmq-endpoint tcp://*:5556
"""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import zmq

from scene_export import (
    Node,
    LaneTopologyBuilder,
    vehicle_actor_meta,
    vehicle_node,
)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class CarlaCollector:
    """Connects to a CARLA server and, on each collect() call, returns the
    current Vehicle nodes plus the (cached, one-time-built) static lane
    topology from LaneTopologyBuilder. This is connection/plumbing code --
    for what data gets collected or how lanes are segmented, see
    scene_export.py instead."""

    def __init__(self, host: str, port: int, timeout: float, actor_refresh_ticks: int = 30):
        try:
            import carla  # type: ignore
        except Exception as exc:
            raise RuntimeError(
                "CARLA Python API not available. Install CARLA Python package."
            ) from exc

        self._carla = carla
        self.client = carla.Client(host, port)
        self.client.set_timeout(timeout)
        self.world = self.client.get_world()

        self._lane_nodes: Optional[List[Node]] = None
        self._actor_meta: dict = {}  # actor_id -> vehicle_actor_meta() dict
        self._tick = 0
        # Re-scanning world.get_actors() every tick is wasteful when the
        # vehicle roster rarely changes, so it's only refreshed every N ticks.
        self._actor_refresh_ticks = max(1, int(actor_refresh_ticks))

    def collect(self) -> List[Node]:
        """Returns this tick's Vehicle nodes plus the static lane topology."""
        self._tick += 1

        if self._lane_nodes is None:
            self._lane_nodes = LaneTopologyBuilder(self.world, self._carla).build()

        if not self._actor_meta or (self._tick % self._actor_refresh_ticks) == 1:
            self._refresh_actor_meta()

        nodes = self._snapshot_vehicles()
        nodes.extend(self._lane_nodes)
        return nodes

    def _snapshot_vehicles(self) -> List[Node]:
        """Reads each known actor's current transform/velocity into a Vehicle Node."""
        snapshot = self.world.get_snapshot()
        nodes: List[Node] = []

        for actor_id, meta in self._actor_meta.items():
            actor_snap = snapshot.find(actor_id)
            if actor_snap is None:
                continue
            nodes.append(vehicle_node(meta, actor_snap.get_transform(), actor_snap.get_velocity()))
        return nodes

    def _refresh_actor_meta(self) -> None:
        """Rebuilds the actor_id -> Vehicle metadata roster."""
        self._actor_meta.clear()
        for actor in self.world.get_actors().filter("vehicle.*"):
            self._actor_meta[actor.id] = vehicle_actor_meta(actor)


def diff_nodes(prev: Dict[str, Dict[str, object]], curr: Dict[str, Dict[str, object]]) -> Dict[str, List[object]]:
    """Compares two id -> Node.to_dict() maps. This one diff is the single
    source of truth for "what changed this tick" -- it drives both the
    ZMQ payload (added/updated nodes go out, removed ids are marked
    deleted) and the debug event log."""
    prev_ids, curr_ids = set(prev), set(curr)

    added: List[object] = [curr[i] for i in sorted(curr_ids - prev_ids)]
    removed: List[object] = [prev[i] for i in sorted(prev_ids - curr_ids)]
    updated: List[object] = [
        {"before": prev[i], "after": curr[i]}
        for i in sorted(curr_ids & prev_ids)
        if prev[i] != curr[i]
    ]

    return {"added": added, "removed": removed, "updated": updated}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stream CARLA scene graphs via ZeroMQ")
    parser.add_argument("--out-dir", default=None, help="Directory for debug files (off by default)")
    parser.add_argument("--interval", type=float, default=0.1, help="Seconds between snapshots")
    parser.add_argument("--ticks", type=int, default=0, help="Number of ticks (0 = infinite)")
    parser.add_argument("--carla-address", default="127.0.0.1", help="CARLA host")
    parser.add_argument("--port", type=int, default=2000, help="CARLA RPC port")
    parser.add_argument("--timeout", type=float, default=5.0, help="CARLA RPC timeout")
    parser.add_argument("--zmq-endpoint", default="tcp://*:5556",
                        help="ZeroMQ PUB bind endpoint (default tcp://*:5556)")
    parser.add_argument("--no-zmq", action="store_true",
                        help="Disable ZeroMQ publishing (debug/file-only mode)")
    return parser.parse_args()


def build_snapshot(changed_node_dicts: List[Dict[str, object]], deleted_ids: List[str], tick: int) -> Dict[str, Any]:
    """Build the JSON-serialisable snapshot dict consumed by JsonModelUpdater:
    each changed node's to_dict(), plus a {"id":..., "deleted": True} entry
    for each node that disappeared since last tick."""
    entries = list(changed_node_dicts)
    entries += [{"id": d_id, "deleted": True} for d_id in deleted_ids]
    return {"tick": tick, "timestamp": now_iso(), "nodes": entries}


def open_zmq_publisher(endpoint: str) -> Tuple[zmq.Context, zmq.Socket]:
    """Create a PUB socket bound to *endpoint*. Caller owns the context."""
    ctx = zmq.Context.instance()
    sock = ctx.socket(zmq.PUB)
    sock.setsockopt(zmq.LINGER, 0)
    sock.setsockopt(zmq.SNDHWM, 16)  # drop old snapshots if SUB is slow
    sock.bind(endpoint)
    return ctx, sock


def persist_event(event: Dict[str, object], events_path: Path, state_path: Path) -> None:
    with events_path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(event) + "\n")

    state_path.write_text(json.dumps(event, indent=2), encoding="utf-8")


def main() -> int:
    args = parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else None
    if out_dir is not None:
        events_path = out_dir / "events.jsonl"
        state_path = out_dir / "current_state.json"
        out_dir.mkdir(parents=True, exist_ok=True)

    zmq_socket: Optional[zmq.Socket] = None
    zmq_ctx: Optional[zmq.Context] = None
    if not args.no_zmq:
        zmq_ctx, zmq_socket = open_zmq_publisher(args.zmq_endpoint)
        print(f"[bridge] ZMQ PUB bound: {args.zmq_endpoint}", flush=True)
        # PUB sockets drop messages sent before any subscriber connects (slow joiner).
        # A short warmup gives the Java SUB time to complete its TCP handshake.
        time.sleep(0.3)
    else:
        print("[bridge] ZMQ disabled (--no-zmq); debug files only.", flush=True)

    # Stateful CARLA collector -- caches lane topology and re-uses the RPC
    # client across ticks.
    collector = CarlaCollector(args.carla_address, args.port, args.timeout)

    prev_nodes: Dict[str, Dict[str, object]] = {}

    tick = 0
    try:
        # Warmup sleep to allow ZMQ subscribers to connect before sending the first snapshot.
        time.sleep(2.0)
        while True:
            tick += 1

            nodes = collector.collect()
            curr_nodes = {n.external_id: n.to_dict() for n in nodes}
            node_diff = diff_nodes(prev_nodes, curr_nodes)

            changed = node_diff["added"] + [u["after"] for u in node_diff["updated"]]
            deleted_ids = [d["id"] for d in node_diff["removed"]]
            snapshot = build_snapshot(changed, deleted_ids, tick)

            if zmq_socket is not None:
                # send_string is non-blocking with default flags; PUB drops if HWM hit.
                zmq_socket.send_string(json.dumps(snapshot, separators=(",", ":")))

            event = {
                "timestamp": now_iso(),
                "tick": tick,
                "node_count": len(nodes),
                "node_changes": node_diff,
            }

            if out_dir is not None:
                persist_event(event, events_path, state_path)

            prev_nodes = curr_nodes

            transport = "zmq" if zmq_socket is not None else "file"
            print(
                f"tick={tick} nodes={len(nodes)} -> {transport}",
                flush=True,
            )

            if args.ticks > 0 and tick >= args.ticks:
                break

            time.sleep(args.interval)
    finally:
        if zmq_socket is not None:
            zmq_socket.close(linger=0)
        if zmq_ctx is not None:
            zmq_ctx.term()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
