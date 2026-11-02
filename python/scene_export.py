#!/usr/bin/env python3
"""SceneGraph Node schema and static lane-topology extraction.

This file is the part of the pipeline a user is expected to edit:
    - Node / NODE_TYPE_TO_PARENT_REF   what data a node carries and what
                                        JsonModelUpdater does with it.
    - DEFAULT_VEHICLE_LENGTH_M/WIDTH_M vehicle fallback size.
    - LANE_SEGMENT_*                   lane-segmentation tuning.
    - LaneTopologyBuilder               Road/Lane/LaneSegment extraction
                                        from the CARLA map.

The CARLA connection and per-tick vehicle-actor collection live in
publisher.py instead, since that's plumbing rather than
something you'd normally need to change.

Note:
    Only x/y (2D, top-down) coordinates are exported; elevation (z) is
    not represented. Each lane is split into LaneSegment nodes by
    walking its waypoint polyline and starting a new segment whenever
    the heading has drifted more than LANE_SEGMENT_ANGLE_THRESHOLD_RAD
    radians from the start of the current segment (see
    LANE_SEGMENT_SAMPLE_STEP_M for the polyline sampling resolution).
    Long straight stretches collapse into a single LaneSegment while
    curves are subdivided more finely -- still a piecewise-linear
    approximation of curvature, not a true curved representation.
"""

from __future__ import annotations

import math
import sys
from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple, Iterator

# ==============================================================================
# Node schema
# ==============================================================================
#
# Add a new node type by: adding it to NODE_TYPE_TO_PARENT_REF below, and
# constructing Node(node_type=..., ...) wherever that data is collected
# (publisher.py for dynamic actors, LaneTopologyBuilder below for
# static map data). Node.to_dict() then handles it automatically -- no
# other file needs to change.

# Maps a node's type to the name of the containment EReference on its PARENT
# that it belongs in, per the scenegraph metamodel (sceneGraphModel.ecore):
#   SceneGraph.nodes  : EReference[Node],  containment, many (the root)
#   Road.lanes        : EReference[Lane],  containment, many
#   Lane.laneSegments : EReference[LaneSegment], containment, many
NODE_TYPE_TO_PARENT_REF: Dict[str, str] = {
    "Road": "nodes",
    "Vehicle": "nodes",
    "Lane": "lanes",
    "LaneSegment": "laneSegments",
}


@dataclass
class Node:
    """Core node structure. to_dict() is the canonical list of outputted
    data for a node -- the only place field names/rounding are defined."""
    node_type: str
    external_id: str
    x: float
    y: float
    heading: float
    length: float
    width: float
    speed: Optional[float] = None
    # Id of this node's parent in the Road -> Lane -> LaneSegment hierarchy
    parent_id: Optional[str] = None

    def to_dict(self) -> Dict[str, object]:
        """Full set of outputted data for this node. speed is omitted
        (not sent as null/0.0) for node types that don't have one, e.g.
        a Road."""
        d: Dict[str, object] = {
            "id": self.external_id,
            "type": self.node_type,
            "x": round(self.x, 6),
            "y": round(self.y, 6),
            "heading": round(self.heading, 6),
            "length": round(self.length, 6),
            "width": round(self.width, 6),
        }
        if self.speed is not None:
            d["speed"] = round(self.speed, 6)
        d["parent-id"] = self.parent_id
        d["parent-ref"] = NODE_TYPE_TO_PARENT_REF.get(self.node_type)
        return d


# ==============================================================================
# Vehicle actors -- turning a raw CARLA actor/transform/velocity into
# Vehicle node data. Connecting to CARLA and driving the per-tick polling
# loop is plumbing, kept in publisher.py.
# ==============================================================================

# Node.size is mandatory (lowerBound=1) for every Node, including Vehicle.
# This is only used as a last-resort fallback when a vehicle actor's own
# bounding box comes back degenerate (near-zero).
DEFAULT_VEHICLE_LENGTH_M = 4.5
DEFAULT_VEHICLE_WIDTH_M = 1.8


def vehicle_actor_meta(actor) -> Dict[str, object]:
    """Extracts a vehicle actor's (mostly static) identity and size: its
    external_id (from role_name if set, else actor id) and its length/width
    from the actor's own bounding box, falling back to the default
    footprint if that box comes back degenerate."""
    role_name = actor.attributes.get("role_name", "").strip()
    external_id = f"veh-{role_name}" if role_name else f"veh-{actor.id}"

    extent = actor.bounding_box.extent
    length = extent.x * 2.0
    width = extent.y * 2.0

    return {
        "external_id": external_id,
        "length": length if length > 1e-3 else DEFAULT_VEHICLE_LENGTH_M,
        "width": width if width > 1e-3 else DEFAULT_VEHICLE_WIDTH_M,
    }


def vehicle_node(meta: Dict[str, object], transform, velocity) -> Node:
    """Builds this tick's Vehicle Node from cached actor meta (see
    vehicle_actor_meta) plus the actor's current transform/velocity."""
    return Node(
        node_type="Vehicle",
        external_id=meta["external_id"],
        x=transform.location.x,
        y=transform.location.y,
        heading=math.radians(transform.rotation.yaw),
        speed=math.hypot(velocity.x, velocity.y),
        length=meta["length"],
        width=meta["width"],
    )


# ==============================================================================
# Lane topology (static, built once per session)
# ==============================================================================

# Sampling resolution (in meters) used while walking a lane's waypoint
# polyline to detect curvature. This is NOT the segment length -- it's
# how finely we poll waypoints to decide where a segment should end.
LANE_SEGMENT_SAMPLE_STEP_M = 0.5

# A new LaneSegment starts once the heading has drifted by more than
# this many radians from the heading at the start of the current
# segment. Larger threshold => fewer, longer segments on straight road.
LANE_SEGMENT_ANGLE_THRESHOLD_RAD = 0.15


@dataclass
class _Accumulator:
    """Running average helper: aggregates a set of LaneSegments into their
    parent Lane node, and a set of Lanes into their parent Road node."""
    sum_x: float = 0.0
    sum_y: float = 0.0
    sum_length: float = 0.0
    sum_width: float = 0.0
    heading: float = 0.0
    count: int = 0

    def add(self, x: float, y: float, heading: float, length: float, width: float) -> None:
        if self.count == 0:
            self.heading = heading  # take the first child's heading as representative
        self.sum_x += x
        self.sum_y += y
        self.sum_length += length
        self.sum_width += width
        self.count += 1

    def as_node(self, node_type: str, external_id: str, parent_id: Optional[str] = None) -> Node:
        return Node(
            node_type, external_id,
            x=self.sum_x / self.count, y=self.sum_y / self.count,
            heading=self.heading, length=self.sum_length, width=self.sum_width / self.count,
            parent_id=parent_id,
        )


class _SegmentBuilder:
    """Accumulates consecutive polyline steps of one lane into a single
    LaneSegment, and starts a new one once the heading drifts too far.

    Usage: call add_step() for each polyline step in order; it returns a
    finished Node whenever a segment boundary is crossed. Call flush() once
    at the end of the lane to emit the trailing segment, if any.
    """

    def __init__(self, road_id: int, lane_id: int):
        self.road_id = road_id
        self.lane_id = lane_id
        self._index = 0
        self._start_wp = None
        self._end_wp = None
        self._heading: Optional[float] = None
        self._arc_length = 0.0
        self._width_sum = 0.0
        self._width_count = 0

    @staticmethod
    def _angle_diff(a: float, b: float) -> float:
        """Smallest absolute difference between two headings (radians)."""
        return abs(math.atan2(math.sin(a - b), math.cos(a - b)))

    def add_step(self, from_wp, to_wp, heading: float, step_length: float, lane_width: float) -> Optional[Node]:
        """Feed one polyline step. Returns a finished LaneSegment Node if
        this step crossed the angle threshold (closing out the segment
        so far), else None."""
        finished = None
        if self._start_wp is None:
            self._start_wp = from_wp
        elif self._angle_diff(heading, self._heading) > LANE_SEGMENT_ANGLE_THRESHOLD_RAD:
            finished = self._finish()
            self._start_wp = from_wp

        self._heading = heading if self._heading is None else self._heading
        self._end_wp = to_wp
        self._arc_length += step_length
        self._width_sum += lane_width
        self._width_count += 1
        return finished

    def flush(self) -> Optional[Node]:
        """Call after the last add_step() to emit the trailing segment."""
        return self._finish() if self._arc_length > 0.0 else None

    def _finish(self) -> Node:
        loc1, loc2 = self._start_wp.transform.location, self._end_wp.transform.location
        cx, cy = (loc1.x + loc2.x) / 2, (loc1.y + loc2.y) / 2
        heading = math.atan2(loc2.y - loc1.y, loc2.x - loc1.x)
        # Actual lane width, averaged over the samples making up this
        # segment (lane width can vary slightly along a lane).
        width = self._width_sum / self._width_count if self._width_count else self._start_wp.lane_width

        node = Node(
            node_type="LaneSegment", external_id=f"laneSegment_{self.road_id}_{self.lane_id}_{self._index}",
            x=cx, y=cy, heading=heading, length=self._arc_length, width=width,
            parent_id=f"lane_{self.road_id}_{self.lane_id}",
        )

        self._index += 1
        self._heading, self._arc_length, self._width_sum, self._width_count = None, 0.0, 0.0, 0
        return node


class LaneTopologyBuilder:
    """One-shot extraction of Road/Lane/LaneSegment Nodes from a CARLA
    map's topology. Built once per session and cached by the caller --
    the road network doesn't change while the simulation runs."""

    def __init__(self, world, carla_module):
        self.world = world
        self._carla = carla_module

    def build(self) -> List[Node]:
        """Walks every driving lane's waypoint polyline into LaneSegments,
        then aggregates those into parent Lane and Road nodes."""
        nodes: List[Node] = []
        try:
            lane_acc: Dict[Tuple[int, int], _Accumulator] = defaultdict(_Accumulator)
            road_acc: Dict[int, _Accumulator] = defaultdict(_Accumulator)

            for start_wp, end_wp in self.world.get_map().get_topology():
                if start_wp.lane_type != self._carla.LaneType.Driving:
                    continue
                self._build_lane(start_wp, end_wp, nodes, lane_acc, road_acc)

            for (road_id, lane_id), acc in lane_acc.items():
                nodes.append(acc.as_node("Lane", f"lane_{road_id}_{lane_id}", parent_id=f"road_{road_id}"))
            for road_id, acc in road_acc.items():
                nodes.append(acc.as_node("Road", f"road_{road_id}"))

        except Exception as e:
            print(f"Warning: Could not collect lane data: {e}", file=sys.stderr)
            import traceback
            traceback.print_exc()
        return nodes

    def _build_lane(self, start_wp, end_wp, nodes: List[Node],
                     lane_acc: Dict[Tuple[int, int], "_Accumulator"], road_acc: Dict[int, "_Accumulator"]) -> None:
        """Splits one lane into LaneSegment nodes and folds each into its
        parent Lane/Road running averages."""
        road_id, lane_id = start_wp.road_id, start_wp.lane_id
        builder = _SegmentBuilder(road_id, lane_id)

        def emit(segment_node: Node) -> None:
            nodes.append(segment_node)
            lane_acc[(road_id, lane_id)].add(segment_node.x, segment_node.y, segment_node.heading, segment_node.length, segment_node.width)
            road_acc[road_id].add(segment_node.x, segment_node.y, segment_node.heading, segment_node.length, segment_node.width)

        for from_wp, to_wp, heading, step_length, lane_width in self._iter_polyline_steps(start_wp, end_wp, road_id, lane_id):
            finished = builder.add_step(from_wp, to_wp, heading, step_length, lane_width)
            if finished is not None:
                emit(finished)

        trailing = builder.flush()
        if trailing is not None:
            emit(trailing)

    def _iter_polyline_steps(self, start_waypoint, end_waypoint, road_id: int, lane_id: int) -> Iterator[Tuple]:
        """Step along the lane's polyline, yielding (from_wp, to_wp,
        heading, step_length, lane_width) for each non-degenerate step."""
        target_loc = end_waypoint.transform.location
        curr_wp = start_waypoint

        while True:
            dist_to_end = curr_wp.transform.location.distance(target_loc)
            if dist_to_end < 1e-2:
                break

            step_size = min(LANE_SEGMENT_SAMPLE_STEP_M, dist_to_end)
            next_wps = curr_wp.next(step_size)

            # Strictly enforce same road and lane ID; clamp to end_waypoint at boundary
            next_wp = next(
                (wp for wp in next_wps if wp.road_id == road_id and wp.lane_id == lane_id),
                end_waypoint
            ) if next_wps else end_waypoint

            loc1, loc2 = curr_wp.transform.location, next_wp.transform.location
            step_length = math.dist((loc1.x, loc1.y, loc1.z), (loc2.x, loc2.y, loc2.z))

            if step_length >= 1e-3:
                heading = math.atan2(loc2.y - loc1.y, loc2.x - loc1.x)
                yield curr_wp, next_wp, heading, step_length, curr_wp.lane_width

            curr_wp = next_wp
            if curr_wp == end_waypoint:
                break
