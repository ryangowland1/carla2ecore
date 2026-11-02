package ca.mcmaster.carla2ecore.examples;

import java.util.List;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * A small Ecore metamodel for the nodes that {@code python/publisher.py} sends, built in code so
 * the examples need no generated classes or model files.
 *
 * <p>A SceneGraph contains Roads, which contain Lanes, which contain LaneSegments, and Vehicles.
 * Every node has an {@code id} and the position and size attributes the publisher sends. Edges
 * between nodes are left to the user's own code, for example VIATRA queries.
 */
public final class RoadScene {

  // Declared before PACKAGE, which build() fills in with it.
  private static final EcoreFactory FACTORY = EcoreFactory.eINSTANCE;

  /** The metamodel. */
  public static final EPackage PACKAGE = build();

  private RoadScene() {}

  /**
   * Returns a new, empty scene graph.
   *
   * @param id the id of the scene graph, which messages may name as a {@code parent-id}
   */
  public static EObject newScene(String id) {
    EClass sceneGraph = eClass("SceneGraph");
    EObject scene = EcoreUtil.create(sceneGraph);
    scene.eSet(sceneGraph.getEStructuralFeature("id"), id);
    return scene;
  }

  /**
   * Returns the class of that name in the metamodel.
   *
   * @param name the class name, such as {@code Vehicle}
   */
  public static EClass eClass(String name) {
    return (EClass) PACKAGE.getEClassifier(name);
  }

  private static EPackage build() {
    EcorePackage ecore = EcorePackage.eINSTANCE;
    EPackage roadScene = FACTORY.createEPackage();
    roadScene.setName("roadscene");
    roadScene.setNsPrefix("roadscene");
    roadScene.setNsURI("http://mcscert.ca/carla2ecore/roadscene");

    EClass node = newClass(roadScene, "Node", null);
    node.setAbstract(true);
    attribute(node, "id", ecore.getEString());
    for (String name : List.of("x", "y", "heading", "length", "width")) {
      attribute(node, name, ecore.getEDouble());
    }
    EClass vehicle = newClass(roadScene, "Vehicle", node);
    attribute(vehicle, "speed", ecore.getEDouble());
    EClass laneSegment = newClass(roadScene, "LaneSegment", node);
    EClass lane = newClass(roadScene, "Lane", node);
    reference(lane, "laneSegments", laneSegment, true);
    EClass road = newClass(roadScene, "Road", node);
    reference(road, "lanes", lane, true);

    EClass edge = newClass(roadScene, "Edge", null);
    reference(edge, "source", node, false);
    reference(edge, "target", node, false);

    EClass sceneGraph = newClass(roadScene, "SceneGraph", null);
    attribute(sceneGraph, "id", ecore.getEString());
    reference(sceneGraph, "nodes", node, true);
    reference(sceneGraph, "edges", edge, true);
    return roadScene;
  }

  private static EClass newClass(EPackage owner, String name, EClass superType) {
    EClass eClass = FACTORY.createEClass();
    eClass.setName(name);
    if (superType != null) {
      eClass.getESuperTypes().add(superType);
    }
    owner.getEClassifiers().add(eClass);
    return eClass;
  }

  private static void attribute(EClass owner, String name, EClassifier type) {
    EAttribute attribute = FACTORY.createEAttribute();
    attribute.setName(name);
    attribute.setEType(type);
    owner.getEStructuralFeatures().add(attribute);
  }

  private static void reference(EClass owner, String name, EClass type, boolean containment) {
    EReference reference = FACTORY.createEReference();
    reference.setName(name);
    reference.setEType(type);
    reference.setContainment(containment);
    reference.setUpperBound(containment ? ETypedElement.UNBOUNDED_MULTIPLICITY : 1);
    owner.getEStructuralFeatures().add(reference);
  }
}
