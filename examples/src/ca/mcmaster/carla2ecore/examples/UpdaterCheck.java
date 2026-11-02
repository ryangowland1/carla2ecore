package ca.mcmaster.carla2ecore.examples;

import ca.mcmaster.carla2ecore.JsonModelUpdater;
import java.util.List;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * Checks {@link JsonModelUpdater} on the {@link RoadScene} metamodel: creating, attaching,
 * updating, unknown types, and deleting with and without the cascade rule. Prints "All checks
 * passed" or throws on the first failure.
 */
public final class UpdaterCheck {

  private UpdaterCheck() {}

  /**
   * Runs the checks.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    EObject scene = RoadScene.newScene("scene");
    JsonModelUpdater updater = new JsonModelUpdater(scene);

    updater.applyJson(
        "{\"tick\":1,\"nodes\":["
            + "{\"id\":\"lane_1\",\"type\":\"Lane\",\"parent-id\":\"road_1\","
            + "\"parent-ref\":\"lanes\"},"
            + "{\"id\":\"road_1\",\"type\":\"Road\",\"parent-ref\":\"nodes\"},"
            + "{\"id\":\"veh-1\",\"type\":\"Vehicle\",\"x\":1.5,\"speed\":2,"
            + "\"parent-id\":null,\"parent-ref\":\"nodes\"},"
            + "{\"id\":\"veh-2\",\"type\":\"Vehicle\",\"parent-ref\":\"nodes\"},"
            + "{\"id\":\"bike-1\",\"type\":\"Bicycle\",\"parent-ref\":\"nodes\"}]}");
    EObject road = node(scene, "road_1");
    EObject vehicle = node(scene, "veh-1");
    check(nodes(scene).size() == 3, "2 vehicles and 1 road at the root, no Bicycle");
    check(list(road, "lanes").size() == 1, "a lane listed before its road attaches to it");
    check(get(vehicle, "x").equals(1.5) && get(vehicle, "speed").equals(2.0), "attributes set");

    int[] notifications = {0};
    vehicle
        .eAdapters()
        .add(
            new AdapterImpl() {
              @Override
              public void notifyChanged(Notification msg) {
                notifications[0]++;
              }
            });
    updater.applyJson("{\"nodes\":[{\"id\":\"veh-1\",\"x\":4.0}]}");
    check(get(vehicle, "x").equals(4.0) && notifications[0] == 1, "a changed value notifies");
    updater.applyJson("{\"nodes\":[{\"id\":\"veh-1\",\"x\":4.0}]}");
    check(notifications[0] == 1, "an unchanged value doesn't notify");

    EObject keptEdge = edge(scene, node(scene, "veh-2"), road);
    updater.applyJson("{\"nodes\":[{\"id\":\"veh-2\",\"deleted\":true}]}");
    check(node(scene, "veh-2") == null, "a deleted node leaves the model");
    check(keptEdge.eContainer() == scene && get(keptEdge, "source") == null,
        "without a cascade rule, an edge loses its reference to the deleted node");

    EObject goneEdge = edge(scene, vehicle, road);
    updater.setCascadeDelete(o -> o.eClass() == RoadScene.eClass("Edge"));
    updater.applyJson("{\"nodes\":[{\"id\":\"veh-1\",\"deleted\":true}]}");
    check(goneEdge.eContainer() == null, "the cascade rule deletes an edge to a deleted node");

    System.out.println("All checks passed");
  }

  private static EObject edge(EObject scene, EObject source, EObject target) {
    EObject edge = EcoreUtil.create(RoadScene.eClass("Edge"));
    edge.eSet(edge.eClass().getEStructuralFeature("source"), source);
    edge.eSet(edge.eClass().getEStructuralFeature("target"), target);
    list(scene, "edges").add(edge);
    return edge;
  }

  private static EObject node(EObject scene, String id) {
    for (EObject node : nodes(scene)) {
      if (id.equals(get(node, "id"))) {
        return node;
      }
    }
    return null;
  }

  private static List<EObject> nodes(EObject scene) {
    return list(scene, "nodes");
  }

  @SuppressWarnings("unchecked")
  private static List<EObject> list(EObject owner, String reference) {
    return (List<EObject>) get(owner, reference);
  }

  private static Object get(EObject owner, String feature) {
    return owner.eGet(owner.eClass().getEStructuralFeature(feature));
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError("Failed: " + what);
    }
    System.out.println("ok: " + what);
  }
}
