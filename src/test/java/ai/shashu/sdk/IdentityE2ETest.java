package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live SHASHU proof. Skipped unless {@code SHASHU_E2E_BASE_URL} is set — typically by {@code
 * _internal/identity-i4-i5/e2e_real_stack.py} after it boots the throwaway stack.
 */
@Tag("e2e")
class IdentityE2ETest {

  private static final String WRONG_AUD = "This pass is meant for a different audience.";
  private static final String UNKNOWN = "No agent matches this pass.";

  private static String env(String key) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? null : v;
  }

  @BeforeAll
  static void requireShashu() {
    assumeTrue(
        env("SHASHU_E2E_BASE_URL") != null,
        "no SHASHU is reachable (set SHASHU_E2E_BASE_URL after starting the throwaway stack)");
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder()
        .model("mock-echo")
        .addMessage(ChatMessage.user("i4-i5 e2e"))
        .build();
  }

  private static ShashuClient.Builder base() {
    return ShashuClient.builder().baseUrl(env("SHASHU_E2E_BASE_URL")).maxRetries(0);
  }

  @Test
  void a_allowed_non_streaming() {
    assumeTrue(env("SHASHU_E2E_PASS") != null, "SHASHU_E2E_PASS not set");
    AtomicInteger n = new AtomicInteger();
    String pass = env("SHASHU_E2E_PASS");
    try (ShashuClient client =
        base()
            .tokenProvider(
                () -> {
                  n.incrementAndGet();
                  return pass;
                })
            .build()) {
      client.chat().completions().create(req());
    }
    assertEquals(1, n.get());
  }

  @Test
  void a_allowed_streaming() {
    assumeTrue(env("SHASHU_E2E_PASS") != null, "SHASHU_E2E_PASS not set");
    AtomicInteger n = new AtomicInteger();
    String pass = env("SHASHU_E2E_PASS");
    try (ShashuClient client =
            base()
                .tokenProvider(
                    () -> {
                      n.incrementAndGet();
                      return pass;
                    })
                .build();
        var stream = client.chat().completions().createStream(req())) {
      stream.forEach(c -> {});
    }
    assertEquals(1, n.get());
  }

  @Test
  void b_expired_retries_once() {
    assumeTrue(env("SHASHU_E2E_PASS_EXPIRED") != null && env("SHASHU_E2E_PASS_FRESH") != null);
    List<String> passes = new ArrayList<>();
    passes.add(env("SHASHU_E2E_PASS_EXPIRED"));
    passes.add(env("SHASHU_E2E_PASS_FRESH"));
    AtomicInteger n = new AtomicInteger();
    try (ShashuClient client =
        base()
            .tokenProvider(
                () -> {
                  int i = n.getAndIncrement();
                  return passes.get(Math.min(i, passes.size() - 1));
                })
            .build()) {
      client.chat().completions().create(req());
    }
    assertEquals(2, n.get());
  }

  @Test
  void c_wrong_audience_no_retry() {
    assumeTrue(env("SHASHU_E2E_PASS_WRONG_AUD") != null);
    AtomicInteger n = new AtomicInteger();
    String pass = env("SHASHU_E2E_PASS_WRONG_AUD");
    try (ShashuClient client =
        base()
            .tokenProvider(
                () -> {
                  n.incrementAndGet();
                  return pass;
                })
            .build()) {
      ShashuAuthenticationException ex =
          assertThrows(
              ShashuAuthenticationException.class, () -> client.chat().completions().create(req()));
      assertEquals(PassCodes.PASS_WRONG_AUDIENCE, ex.getCode());
      assertEquals(WRONG_AUD, ex.getMessage());
    }
    assertEquals(1, n.get());
  }

  @Test
  void d_unknown_agent_no_retry() {
    assumeTrue(env("SHASHU_E2E_PASS_UNKNOWN") != null);
    AtomicInteger n = new AtomicInteger();
    String pass = env("SHASHU_E2E_PASS_UNKNOWN");
    try (ShashuClient client =
        base()
            .tokenProvider(
                () -> {
                  n.incrementAndGet();
                  return pass;
                })
            .build()) {
      ShashuAuthenticationException ex =
          assertThrows(
              ShashuAuthenticationException.class, () -> client.chat().completions().create(req()));
      assertEquals(PassCodes.PASS_UNKNOWN_AGENT, ex.getCode());
      assertEquals(UNKNOWN, ex.getMessage());
    }
    assertEquals(1, n.get());
  }

  @Test
  void key_mode_still_works() {
    assumeTrue(env("SHASHU_E2E_KEY") != null, "SHASHU_E2E_KEY not set");
    String key = env("SHASHU_E2E_KEY");
    assertTrue(key.startsWith("shashu_sk_"));
    try (ShashuClient client = base().apiKey(key).build()) {
      client.chat().completions().create(req());
    }
  }
}
