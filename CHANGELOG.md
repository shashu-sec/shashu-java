# Changelog — SHASHU Java SDK

This project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.1] — 2026-11-25

Fixed: when the gateway was busy and the SDK retried, the retry could be sent
without the retrieval declaration. The request (body, headers, and
`shashu_metadata`) is now built once per logical call and only re-sent.
`declareRetrieval` on the same thread is joined onto `ShashuClient` itself.

OkHttp no longer immediately replays a busy 503 with `Retry-After: 0` on top of
the SDK retry loop (that extra hop was already present in 1.1.0; only the SDK
retries now).

## [1.1.0] — 2026-11-25

Wire names moved to SHASHU (requires a SHASHU gateway); key prefix `tg_sk_` unchanged.

Retries when the gateway says busy (Retry-After + jitter, default 2 retries, configurable).

The one-shot `pass_expired` retry is unchanged and does not stack with busy-retry.
`x-shashu-hop-ticket` is not sent (never sent in 1.0.0). Planned later.

### Breaking

- Environment variables are `SHASHU_API_KEY` / `SHASHU_BASE_URL` / `SHASHU_DISABLE` only. The `TRUSTGATE_*` fallbacks are removed.
- Wire names are SHASHU only. Old TrustGate names are neither sent nor read.

## [1.0.0] — 2026-11-13

First public SHASHU release (`ai.shashu:shashu-sdk:1.0.0`, package
`ai.shashu.sdk`, Apache-2.0, Java 17).

- Key sign-in with a `tg_sk_` agent key, as before.
- Cloud pass: `ShashuClient.builder().tokenProvider(...)`. The pass is cached
  until 60 seconds before JWT `exp`. On HTTP 401 `pass_expired` the SDK fetches
  a fresh pass and retries once, before any streamed byte. Other `pass_*` codes
  raise `ShashuAuthenticationException` with the code and the gateway's
  sentence.
- Jackson Databind / JSR-310 2.23.0.
- Tested on JDK 17 and 21.

Earlier 0.x alphas used previous product names. This 1.0.0 is the first SHASHU
coordinate.
