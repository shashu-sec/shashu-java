# SHASHU Java SDK

Java client for **SHASHU by Trustgate AI**, a self-hosted AI security gateway.
Point it at your own gateway. There is no default hostname: construction
raises a configuration error rather than guessing.

Wire names (`x-shashu-*`, `shashu_metadata`, `shashu-version`,
`tg_sk_`) are protocol names on the SHASHU wire and are unchanged.

After this first mention, this document says **SHASHU**.

## Install

```xml
<dependency>
  <groupId>ai.shashu</groupId>
  <artifactId>shashu-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

```groovy
implementation 'ai.shashu:shashu-sdk:1.0.0'
```

Requires **Java 17** or later (tested on 17 and 21).

## Quick start

Key:

```java
try (ShashuClient client = ShashuClient.builder()
        .baseUrl("https://your-shashu-gateway.example")
        .apiKey("tg_sk_...")
        .build()) {
    var response = client.chat().completions().create(
        ChatCompletionRequest.builder()
            .model("gpt-4o")
            .addMessage(ChatMessage.user("Hello"))
            .build());
    System.out.println(response.choices().get(0).message().content());
}
```

Cloud pass (you fetch the pass; SHASHU never calls Entra, Google or AWS):

```java
try (ShashuClient client = ShashuClient.builder()
        .baseUrl("https://your-shashu-gateway.example")
        .tokenProvider(() -> getPass())
        .build()) {
    // same chat call
}
```

Give either a key or a token provider, not both. Environment fallbacks:
`SHASHU_API_KEY` / `SHASHU_BASE_URL`, then `SHASHU_API_KEY` /
`SHASHU_BASE_URL`.

## Pass refusals

A cloud pass is a JWT sent as `Authorization: Bearer`. On HTTP 401 the SDK
reads `error.code`. It retries **once**, with a fresh pass, only on
`pass_expired`, and only before any streamed byte. Every other `pass_*` code
raises `ShashuAuthenticationException` with that code and the gateway's
sentence, unchanged.

| Code | Sentence |
|---|---|
| `pass_provider_not_set_up` | No identity provider is set up for agents. |
| `pass_not_licensed` | Pass sign-in needs the Enterprise plan. |
| `pass_expired` | This pass has expired. Fetch a new one. |
| `pass_wrong_audience` | This pass is meant for a different audience. |
| `pass_wrong_issuer` | This pass comes from an unknown issuer. |
| `pass_unknown_agent` | No agent matches this pass. |

Constants live on `Shashu` / `PassCodes`.

## Configuration

| Builder | Default | Env |
|---|---|---|
| `.apiKey(String)` | exactly one of this or `.tokenProvider` | `SHASHU_API_KEY` |
| `.tokenProvider(Supplier<String>)` | — | — |
| `.baseUrl(String)` | **none** | `SHASHU_BASE_URL` |
| `.timeoutMillis(long)` | 60_000 | — |
| `.maxRetries(int)` | 2 | — |
| `.userAgent(String)` | `shashu-java/1.0.0` | — |

`SHASHU_DISABLE=1` makes the retrieval, routing and tracer surfaces no-ops.

## Docs

https://shashu.ai/docs

## License

Apache-2.0. See `LICENSE` and `NOTICE`.
