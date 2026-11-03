# Carla2Ecore

Stream a running [CARLA](https://carla.org/) simulation into an EMF model of your own Ecore metamodel.

Carla2Ecore is an Eclipse plugin project. A Python publisher reads the vehicles and road network from a CARLA server and sends what changed as JSON over ZeroMQ. The plugin receives the messages and creates, updates, and deletes the matching objects in your EMF model, so EMF change notifications drive your own code, such as VIATRA queries.

| Folder | What it holds |
|---|---|
| `src/` | The plugin: `ZmqSubscriber` receives messages, `JsonModelUpdater` applies them to a model |
| `lib/` | JeroMQ 0.5.4, the Java ZeroMQ library, bundled so the plugin needs nothing else |
| `python/` | The CARLA publisher (`publisher.py`) and the mapping from CARLA to node types (`scene_export.py`) |
| `examples/` | A small road scene metamodel, a runnable example, a check of the updater, and a recorded CARLA run |

## Requirements

- Eclipse with EMF, EMF Transaction, and PDE, such as Eclipse Modeling Tools 2025-06, which brings its own Java. If EMF Transaction is missing, install "EMF Model Transaction" from the release update site under **Help > Install New Software**.
- For live CARLA only: CARLA 0.9.16 and Python 3.12 with the packages in `python/requirements.txt`

## Import into Eclipse

1. Clone this repository.
2. In Eclipse, choose **File > Import > General > Existing Projects into Workspace**, select the cloned folder, and click **Finish**.

The project `ca.mcmaster.carla2ecore` builds without further setup.

## Try it without CARLA

Right-click each class in `examples/src` and choose **Run As > Java Application**:

- `UpdaterCheck` checks creating, updating, and deleting objects, and ends with `All checks passed`.
- `Example` replays the recorded run in `examples/data` (Scenario A of the paper, 2 external vehicles, 88 messages) and prints `{Lane=438, LaneSegment=1306, Road=160, Vehicle=4}`.

## Use it with your model

Add `ca.mcmaster.carla2ecore;bundle-version="[0.1.0,1.0.0)"` to the `Require-Bundle` header of your plugin (see [Versioning](#versioning)), then:

```java
EObject root = ...;  // the root object of your model, such as a SceneGraph
JsonModelUpdater updater = new JsonModelUpdater(root);
ZmqSubscriber subscriber = new ZmqSubscriber("tcp://127.0.0.1:5556", updater::applyJson);
subscriber.start();  // receives on a daemon thread; subscriber.stop() closes it
```

If the root belongs to a `TransactionalEditingDomain`, each message is applied in 1 `RecordingCommand`. To delete objects together with a node they reference, such as edges whose vehicle left the scene, set a rule with `updater.setCascadeDelete(object -> object instanceof Edge)`.

Your metamodel needs:

- An `id` attribute on every class you stream
- Classes named like the node types the publisher sends, in the root's EPackage
- Containment references named like the `parent-ref` values, and attributes named like the other keys

The publisher sends `Road`, `Lane`, `LaneSegment`, and `Vehicle` nodes with `x`, `y`, `heading`, `length`, and `width`, plus `speed` for vehicles. `examples/src/.../RoadScene.java` builds a metamodel that matches. For another metamodel, change the node types and attributes in `python/scene_export.py`.

## Stream from CARLA

```powershell
pip install -r python\requirements.txt
# Start CARLA, then your subscriber, then the publisher:
python python\publisher.py --zmq-endpoint tcp://*:5556
```

To watch it work, run `Example` with the arguments `live tcp://127.0.0.1:5556` before starting the publisher. Start the subscriber first, since the road network is in the publisher's first message only.

The publisher is an extra CARLA client that never ticks the world or changes its settings. It streams whatever runs in the server, from any source: a [ScenarioRunner](https://github.com/carla-simulator/scenario_runner) scenario, including OpenSCENARIO files (`python scenario_runner.py --openscenario my_scenario.xosc`), the [CARLA Scenario Editor](https://github.com/McSCert/CARLA-Scenario-Editor), the Traffic Manager, or your own scripts.

| Option | Default | Meaning |
|---|---|---|
| `--carla-address`, `--port` | `127.0.0.1`, `2000` | The CARLA server |
| `--zmq-endpoint` | `tcp://*:5556` | Where the publisher binds |
| `--interval` | `0.1` | Seconds between messages |
| `--ticks` | `0` | Messages to send, 0 for no limit |
| `--out-dir` | off | Directory for debug files of every message's changes |

## Message format

Each message is 1 JSON object with a `nodes` list:

```json
{"tick": 42, "timestamp": "2026-10-28T19:46:56.922709+00:00", "nodes": [
  {"id": "veh-ego", "type": "Vehicle", "x": 253.33, "y": 143.96, "heading": 0.004,
   "length": 4.89, "width": 1.84, "speed": 16.66, "parent-id": null, "parent-ref": "nodes"},
  {"id": "veh-ext-3", "deleted": true}
]}
```

| Key | Meaning |
|---|---|
| `id` | The node's stable id, matched to the object whose `id` attribute equals it |
| `type` | The EClass to create when no object has this id |
| `parent-id` | The id of the object that contains a new node, or null for the root |
| `parent-ref` | The containment reference on that object |
| `deleted` | `true` removes the object and every reference to it |
| any other key | An attribute, set only when its value changes |

The first message holds the whole road network. Later messages hold only nodes that changed. `tick` and `timestamp` are ignored by the plugin.

## See it in use

MT4SG is a larger example. It streams CARLA through this plugin into a scene graph model and monitors it with VIATRA.

## Versioning

The plugin follows the [Eclipse version numbering guidelines](https://wiki.eclipse.org/Version_Numbering). `Bundle-Version` in `META-INF/MANIFEST.MF` has 4 segments, `major.minor.service.qualifier`:

| Segment | Increases when |
|---|---|
| major | the API breaks |
| minor | a release adds features that other plugins can see |
| service | a release keeps the API compatible, such as a bug fix or a small addition |
| qualifier | the plugin is built. PDE replaces `qualifier` with the build's timestamp. |

Versions below 1.0.0 are incubation versions, so the plugin may still change before a stable 1.0.0. Each release has a git tag, such as `v0.1.0`, equal to its `Bundle-Version` without the qualifier. A plugin that depends on this one gives the version it needs up to the next major version, as in `[0.1.0,1.0.0)`.

## License

MIT, see `LICENSE`. JeroMQ is distributed under the Mozilla Public License 2.0, see `THIRD_PARTY.md`.
