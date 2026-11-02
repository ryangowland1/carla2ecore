package ca.mcmaster.carla2ecore;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

/**
 * Receives JSON update messages from a ZeroMQ publisher, such as the CARLA adapter in this
 * repository, and hands each one to a callback on a daemon thread.
 *
 * <p>The callback is usually {@link JsonModelUpdater#applyJson(String)}. Messages that arrive
 * while the callback runs wait in the socket's queue.
 */
public final class ZmqSubscriber {

  private static final Logger LOG = Logger.getLogger(ZmqSubscriber.class.getName());

  private final String endpoint;
  private final Consumer<String> onMessage;
  private volatile Thread worker;
  private volatile ZContext context;

  /**
   * Creates a subscriber that has not connected yet.
   *
   * @param endpoint the publisher's ZeroMQ endpoint, such as {@code tcp://127.0.0.1:5556}
   * @param onMessage called with each message, in order, on the subscriber's thread
   */
  public ZmqSubscriber(String endpoint, Consumer<String> onMessage) {
    this.endpoint = endpoint;
    this.onMessage = onMessage;
  }

  /** Connects and starts receiving on a daemon thread. Returns immediately. */
  public synchronized void start() {
    if (worker != null) {
      return;
    }
    Thread t = new Thread(this::runLoop, "carla2ecore-subscriber");
    t.setDaemon(true);
    worker = t;
    t.start();
  }

  /**
   * Stops receiving and closes the socket. Waits up to 500 ms for a message in progress, so the
   * caller can dispose the model without racing the callback.
   */
  public synchronized void stop() {
    Thread t = worker;
    if (context != null) {
      try {
        context.close();
      } catch (RuntimeException ignored) {
        // Already closing.
      }
      context = null;
    }
    if (t != null) {
      t.interrupt();
      try {
        t.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      worker = null;
    }
  }

  private void runLoop() {
    context = new ZContext();
    try (ZMQ.Socket sub = context.createSocket(SocketType.SUB)) {
      // A receive timeout lets the loop notice an interrupt from stop().
      sub.setReceiveTimeOut(500);
      sub.setLinger(0);
      sub.connect(endpoint);
      sub.subscribe(ZMQ.SUBSCRIPTION_ALL);
      LOG.info(() -> "Subscribed to " + endpoint);

      while (!Thread.currentThread().isInterrupted()) {
        byte[] payload;
        try {
          payload = sub.recv(0);
        } catch (ZMQException e) {
          // The context was closed by stop().
          break;
        }
        if (payload == null) {
          continue;
        }
        String json = new String(payload, StandardCharsets.UTF_8);
        LOG.fine(() -> "Received " + json);
        try {
          onMessage.accept(json);
        } catch (RuntimeException e) {
          LOG.log(Level.WARNING, "The message callback failed", e);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "The subscriber stopped", e);
    } finally {
      if (context != null) {
        try {
          context.close();
        } catch (RuntimeException ignored) {
          // Already closing.
        }
        context = null;
      }
      LOG.info(() -> "Unsubscribed from " + endpoint);
    }
  }
}
