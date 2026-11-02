package ca.mcmaster.carla2ecore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.util.TransactionUtil;

/**
 * Applies JSON update messages to an EMF model of any Ecore metamodel, by reflection.
 *
 * <p>A message holds a list of flat node objects. Each node is matched to the model object whose
 * {@code id} attribute equals the node's {@code id}:
 *
 * <ul>
 *   <li>A node with a {@code type} creates an instance of the EClass of that name in the root's
 *       EPackage, or updates the existing object. It is attached to the object named by {@code
 *       parent-id} (the root when absent) through the containment reference named by {@code
 *       parent-ref}.
 *   <li>A node with {@code "deleted": true} removes its object and every reference to it, and the
 *       objects that reference it and match the rule set with {@link #setCascadeDelete}.
 *   <li>Every other key sets the EAttribute of that name, converted with {@link
 *       EcoreUtil#createFromString}, and only when the value changes.
 * </ul>
 *
 * <p>Unknown types and attributes are logged and skipped. The parser is a small regex tokenizer
 * with no dependency: node objects can't nest, and string values can't contain braces or quotes.
 *
 * <p>If the root belongs to a {@link TransactionalEditingDomain}, each message is applied in 1
 * {@link RecordingCommand}.
 */
public class JsonModelUpdater {

  private static final Logger LOG = Logger.getLogger(JsonModelUpdater.class.getName());

  /** A JSON object with no nested object, that is, 1 node. */
  private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{[^{}]*\\}");

  /** A key and its string, number, or boolean value. */
  private static final Pattern PAIR_PATTERN =
      Pattern.compile(
          "\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([\\d.eE+-]+|true|false|True|False))");

  private final EObject root;
  private final EPackage ePackage;
  private final Runnable onCommit;
  private final Map<String, EClass> classifierCache = new HashMap<>();
  private final Map<String, EStructuralFeature> featureCache = new HashMap<>();
  private Predicate<? super EObject> cascadeDelete = object -> false;
  private long parsedNanos;

  /**
   * Creates an updater for the model under {@code root}.
   *
   * @param root the root object, whose EPackage holds the node types
   * @param onCommit called after each message is applied, or null
   */
  public JsonModelUpdater(EObject root, Runnable onCommit) {
    this.root = root;
    this.ePackage = root.eClass().getEPackage();
    this.onCommit = onCommit;
  }

  /**
   * Creates an updater for the model under {@code root}.
   *
   * @param root the root object, whose EPackage holds the node types
   */
  public JsonModelUpdater(EObject root) {
    this(root, null);
  }

  /**
   * Sets which objects are deleted together with a deleted node they reference, such as {@code
   * object -> object instanceof Edge} for edges whose source or target leaves the scene. By default
   * none are.
   *
   * @param rule true for an object that references a deleted node and goes with it
   */
  public void setCascadeDelete(Predicate<? super EObject> rule) {
    this.cascadeDelete = rule;
  }

  /**
   * Applies 1 JSON update message to the model.
   *
   * @param json the message, with its nodes as flat JSON objects
   */
  public void applyJson(String json) {
    List<NodeData> incomingNodes = parseNodes(json);
    parsedNanos = System.nanoTime();
    if (incomingNodes.isEmpty()) {
      return;
    }

    Map<String, EObject> nodesById = indexAllNodes();
    TransactionalEditingDomain domain = TransactionUtil.getEditingDomain(root);

    Runnable updateTask =
        () -> {
          List<NodeData> newlyCreated = new ArrayList<>();
          Set<EObject> explicitDeletes = new HashSet<>();

          // New nodes are attached only after every node of the message exists, since a parent
          // can come later in the list than its children.
          for (NodeData data : incomingNodes) {
            EObject existing = nodesById.get(data.id);
            if (data.isDeleted) {
              if (existing != null) {
                explicitDeletes.add(existing);
              }
            } else if (existing == null) {
              EObject created = createNode(data);
              if (created != null) {
                nodesById.put(data.id, created);
                newlyCreated.add(data);
              }
            } else {
              data.attributes.forEach((key, value) -> setAttribute(existing, key, value));
            }
          }

          for (NodeData data : newlyCreated) {
            EObject parent = data.parentId != null ? nodesById.get(data.parentId) : root;
            if (parent != null) {
              attach(parent, nodesById.get(data.id), data.parentRef);
            }
          }

          Set<EObject> cascaded = new HashSet<>();
          for (EObject target : explicitDeletes) {
            for (EStructuralFeature.Setting setting :
                EcoreUtil.UsageCrossReferencer.find(target, root)) {
              EObject referencing = setting.getEObject();
              if (referencing != root && cascadeDelete.test(referencing)) {
                cascaded.add(referencing);
              }
            }
          }
          cascaded.forEach(EcoreUtil::delete);
          explicitDeletes.forEach(EcoreUtil::delete);
        };

    if (domain != null) {
      domain
          .getCommandStack()
          .execute(
              new RecordingCommand(domain) {
                @Override
                protected void doExecute() {
                  updateTask.run();
                }
              });
    } else {
      updateTask.run();
    }

    if (onCommit != null) {
      onCommit.run();
    }
  }

  /** Returns when the last {@link #applyJson} finished parsing, in {@link System#nanoTime}. */
  public long getParsedNanos() {
    return parsedNanos;
  }

  /** Returns every object under the root that has an id, and the root, by id. */
  private Map<String, EObject> indexAllNodes() {
    Map<String, EObject> map = new HashMap<>();
    String rootId = getId(root);
    if (rootId != null) {
      map.put(rootId, root);
    }
    EcoreUtil.getAllContents(root, true)
        .forEachRemaining(
            object -> {
              EObject eObject = (EObject) object;
              String id = getId(eObject);
              if (id != null) {
                map.put(id, eObject);
              }
            });
    return map;
  }

  private String getId(EObject object) {
    EStructuralFeature feature = resolveDirect(object.eClass(), "id");
    if (feature instanceof EAttribute attribute) {
      Object value = object.eGet(attribute);
      return value != null ? value.toString() : null;
    }
    return null;
  }

  private EObject createNode(NodeData data) {
    EClass eClass =
        classifierCache.computeIfAbsent(
            data.type, type -> ePackage.getEClassifier(type) instanceof EClass c ? c : null);
    if (eClass == null) {
      LOG.log(
          Level.WARNING,
          "Unknown node type ''{0}'' for id ''{1}'', node not created",
          new Object[] {data.type, data.id});
      return null;
    }
    EObject node = EcoreUtil.create(eClass);
    data.attributes.forEach((key, value) -> setAttribute(node, key, value));
    return node;
  }

  private void setAttribute(EObject object, String key, String rawValue) {
    EStructuralFeature feature = resolveDirect(object.eClass(), key);
    if (feature instanceof EAttribute attribute) {
      Object parsedValue = EcoreUtil.createFromString(attribute.getEAttributeType(), rawValue);
      // Setting an equal value would still send a change notification.
      if (!Objects.equals(object.eGet(attribute), parsedValue)) {
        object.eSet(attribute, parsedValue);
      }
    } else {
      LOG.log(
          Level.WARNING,
          "No attribute ''{0}'' on {1}, value ''{2}'' ignored",
          new Object[] {key, object.eClass().getName(), rawValue});
    }
  }

  private EStructuralFeature resolveDirect(EClass eClass, String key) {
    return featureCache.computeIfAbsent(
        eClass.getName() + ":" + key, k -> eClass.getEStructuralFeature(key));
  }

  private void attach(EObject parent, EObject child, String parentRef) {
    if (parentRef == null) {
      LOG.log(
          Level.WARNING,
          "No parent-ref for {0}, node not attached",
          child.eClass().getName());
      return;
    }
    EStructuralFeature feature = resolveDirect(parent.eClass(), parentRef);
    if (!(feature instanceof EReference reference) || !reference.isContainment()) {
      LOG.log(
          Level.WARNING,
          "No containment reference ''{0}'' on {1}, {2} not attached",
          new Object[] {parentRef, parent.eClass().getName(), child.eClass().getName()});
      return;
    }
    if (reference.isMany()) {
      @SuppressWarnings("unchecked")
      List<EObject> list = (List<EObject>) parent.eGet(reference);
      list.add(child);
    } else {
      parent.eSet(reference, child);
    }
  }

  /** Returns the nodes of a message, in order. */
  static List<NodeData> parseNodes(String json) {
    if (json == null || json.isEmpty()) {
      return Collections.emptyList();
    }
    List<NodeData> nodes = new ArrayList<>();
    Matcher objectMatcher = OBJECT_PATTERN.matcher(json);
    while (objectMatcher.find()) {
      NodeData node = new NodeData();
      Matcher pairMatcher = PAIR_PATTERN.matcher(objectMatcher.group());
      while (pairMatcher.find()) {
        String value = pairMatcher.group(2) != null ? pairMatcher.group(2) : pairMatcher.group(3);
        node.attributes.put(pairMatcher.group(1), value);
      }
      node.id = node.attributes.get("id");
      node.type = node.attributes.remove("type");
      node.parentId = node.attributes.remove("parent-id");
      node.parentRef = node.attributes.remove("parent-ref");
      node.isDeleted = Boolean.parseBoolean(node.attributes.remove("deleted"));
      nodes.add(node);
    }
    return nodes;
  }

  /** 1 node of a message. */
  static class NodeData {
    String id;
    String type;
    String parentId;
    String parentRef;
    boolean isDeleted;
    Map<String, String> attributes = new LinkedHashMap<>();
  }
}
