package ca.mcmaster.carla2ecore.examples;

import ca.mcmaster.carla2ecore.JsonModelUpdater;
import ca.mcmaster.carla2ecore.ZmqSubscriber;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.eclipse.emf.ecore.EObject;

/**
 * Streams CARLA scenes into the {@link RoadScene} metamodel and prints what the model holds.
 *
 * <p>With no arguments, it replays the recorded messages in {@code examples/data}. With {@code
 * live <endpoint>}, it subscribes to {@code python/publisher.py}. Start this example before the
 * publisher, which sends the road network only in its first message.
 */
public final class Example {

  private static final String RECORDING = "examples/data/scenario-a-2-external-vehicles.jsonl.gz";

  private Example() {}

  /**
   * Replays a recording, or subscribes to a publisher with {@code live <endpoint>}.
   *
   * @param args nothing, a recording path, or {@code live <endpoint>}
   */
  public static void main(String[] args) throws IOException {
    EObject scene = RoadScene.newScene("scene");
    JsonModelUpdater updater = new JsonModelUpdater(scene);

    if (args.length == 2 && args[0].equals("live")) {
      ZmqSubscriber subscriber =
          new ZmqSubscriber(
              args[1],
              json -> {
                updater.applyJson(json);
                System.out.println(summary(scene));
              });
      subscriber.start();
      System.out.println("Listening on " + args[1] + ". Press Enter to stop.");
      System.in.read();
      subscriber.stop();
      return;
    }

    Path recording = Path.of(args.length == 1 ? args[0] : RECORDING);
    int messages = 0;
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(recording)), StandardCharsets.UTF_8))) {
      for (String line = in.readLine(); line != null; line = in.readLine()) {
        updater.applyJson(line);
        messages++;
      }
    }
    System.out.printf("Applied %d messages from %s%n%s%n", messages, recording, summary(scene));
  }

  /** Returns the number of objects of each class in the model. */
  static Map<String, Integer> summary(EObject scene) {
    Map<String, Integer> counts = new TreeMap<>();
    scene.eAllContents().forEachRemaining(o -> counts.merge(o.eClass().getName(), 1, Integer::sum));
    return counts;
  }
}
