package ai.shashu.sdk.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what the SDK logs, so a test can assert the customer would actually hear it.
 *
 * <p>The SDK logs through {@link System.Logger}, which is what lets a customer's SLF4J or log4j
 * binding pick the messages up. With no binding on the classpath &mdash; the case in this module
 * &mdash; the JDK's default {@code LoggerFinder} routes {@code System.Logger} to {@code
 * java.util.logging}, so attaching a JUL handler to the root logger captures records the real code
 * emitted through the real facade. Nothing is mocked and no new dependency is added.
 *
 * <p>The alternative, registering a {@code System.LoggerFinder} service provider, is process-global
 * and irreversible once the first logger is resolved; this is neither.
 */
public final class LogCapture implements AutoCloseable {

  private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
  private final Logger root = Logger.getLogger("");
  private final Handler handler;
  private final Level previousLevel;

  public LogCapture() {
    this.previousLevel = root.getLevel();
    this.handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setLevel(Level.ALL);
    root.addHandler(handler);
    root.setLevel(Level.ALL);
  }

  /** Every WARNING whose message contains {@code needle}. */
  public List<String> warnings(String needle) {
    List<String> found = new ArrayList<>();
    synchronized (records) {
      for (LogRecord record : records) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()
            && record.getMessage() != null
            && record.getMessage().contains(needle)) {
          found.add(record.getMessage());
        }
      }
    }
    return found;
  }

  @Override
  public void close() {
    root.removeHandler(handler);
    root.setLevel(previousLevel);
  }
}
