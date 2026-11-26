package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class Rebrand120Test {

  private static final String COPYRIGHT = "Trustgate AI LLC";
  private static final String KEY = "shashu_sk_rebrand_test_key_not_live";

  @Test
  void a_passthrough_prefix_raises_authentication_exception() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(401)
              .setBody(
                  "{\"error\":{\"message\":\"SHASHU Provider Error: Invalid API key\","
                      + "\"type\":\"authentication_error\",\"code\":\"401\"},\"choices\":[]}"));
      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey(KEY)
              .baseUrl(server.url("/").toString())
              .maxRetries(0)
              .build()) {
        ShashuAuthenticationException ex =
            assertThrows(
                ShashuAuthenticationException.class,
                () ->
                    client
                        .chat()
                        .completions()
                        .create(
                            ChatCompletionRequest.builder()
                                .model("gpt-4o")
                                .addMessage(ChatMessage.user("hi"))
                                .build()));
        assertTrue(ex.getMessage().startsWith("SHASHU Provider Error:"));
      }
    }
  }

  @Test
  void c_authorization_sends_shashu_sk() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"id\":\"c\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"index\":0}]}"));
      try (ShashuClient client =
          ShashuClient.builder().apiKey(KEY).baseUrl(server.url("/").toString()).build()) {
        client
            .chat()
            .completions()
            .create(
                ChatCompletionRequest.builder()
                    .model("gpt-4o")
                    .addMessage(ChatMessage.user("hi"))
                    .build());
      }
      RecordedRequest req = server.takeRequest();
      assertEquals("Bearer " + KEY, req.getHeader("Authorization"));
      assertTrue(KEY.startsWith("shashu_sk_"));
    }
  }

  @Test
  void d_no_trustgate_in_shipped_tree() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    if (!Files.isDirectory(root.resolve("src").resolve("main"))) {
      root = Path.of(".").toAbsolutePath().normalize();
    }
    List<String> hits = new ArrayList<>();
    List<Path> files = new ArrayList<>();
    files.add(root.resolve("README.md"));
    files.add(root.resolve("CHANGELOG.md"));
    files.add(root.resolve("NOTICE"));
    files.add(root.resolve("LICENSE"));
    files.add(root.resolve("pom.xml"));
    Path src = root.resolve("src").resolve("main");
    if (Files.isDirectory(src)) {
      try (Stream<Path> walk = Files.walk(src)) {
        walk.filter(Files::isRegularFile).forEach(files::add);
      }
    }
    Path contracts = root.resolve("contracts");
    if (Files.isDirectory(contracts)) {
      try (Stream<Path> walk = Files.walk(contracts)) {
        walk.filter(Files::isRegularFile).forEach(files::add);
      }
    }
    Pattern needle = Pattern.compile("trustgate", Pattern.CASE_INSENSITIVE);
    for (Path p : files) {
      if (!Files.isRegularFile(p)) continue;
      String text = Files.readString(p, StandardCharsets.UTF_8);
      String stripped = text.replace(COPYRIGHT, "");
      Matcher m = needle.matcher(stripped);
      while (m.find()) {
        hits.add(root.relativize(p) + ":" + m.start());
      }
    }
    assertEquals(List.of(), hits);
  }
}
