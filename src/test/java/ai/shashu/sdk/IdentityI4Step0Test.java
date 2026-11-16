package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** IDENTITY I4 — the client accepts a cloud pass. */
class IdentityI4Step0Test {

  @Test
  void builder_accepts_tokenProvider() {
    boolean present =
        Arrays.stream(ShashuClient.Builder.class.getMethods())
            .map(Method::getName)
            .anyMatch("tokenProvider"::equals);
    assertTrue(present, "Builder.tokenProvider(...) must exist");
  }

  @Test
  void credential_is_not_a_fixed_apiKey_only() throws Exception {
    boolean hasProvider =
        Arrays.stream(ShashuClientOptions.class.getDeclaredFields())
            .anyMatch(f -> f.getName().equals("tokenProvider") || f.getName().equals("credential"));
    assertTrue(
        hasProvider,
        "ShashuClientOptions must hold a token provider / credential resolver, not only a baked apiKey");
    assertFalse(
        Arrays.stream(ShashuClientOptions.class.getDeclaredFields())
                .filter(f -> f.getName().equals("apiKey"))
                .findFirst()
                .orElseThrow()
                .getType()
                .equals(String.class)
            && !hasProvider,
        "apiKey-only options freeze the credential at construction");
  }
}
