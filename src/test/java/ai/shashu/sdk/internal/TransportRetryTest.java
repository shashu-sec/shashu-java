package ai.shashu.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class TransportRetryTest {

  @Test
  void connectFailuresMayRetry() {
    assertTrue(ShashuApi.requestNeverLeft(new ConnectException("Connection refused")));
    assertTrue(ShashuApi.requestNeverLeft(new UnknownHostException("no.such.host")));
    assertTrue(ShashuApi.requestNeverLeft(new SocketTimeoutException("connect timed out")));
    assertTrue(ShashuApi.requestNeverLeft(new IOException("Failed to connect to 127.0.0.1")));
  }

  @Test
  void afterSendMustNotRetry() {
    assertFalse(ShashuApi.requestNeverLeft(new IOException("unexpected end of stream")));
    assertFalse(ShashuApi.requestNeverLeft(new SocketTimeoutException("timeout")));
  }
}
