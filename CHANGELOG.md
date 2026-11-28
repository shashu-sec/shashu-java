# Changelog — SHASHU Java SDK

This project adheres to [Semantic Versioning](https://semver.org/).

## [1.3.0] — 2026-11-27

Added: messages can carry text, image and file parts, the same format as the
Python and Node SDKs.

Fixed: the retrieval declaration is now attached to large requests too, and is
never carried over to another request.

`ChatMessage.user(ContentPart...)`, `ChatMessage.withParts(role, parts)`,
`ContentPart` and `contentParts()` are new. Existing text-only code compiles and
sends the same bytes as 1.2.0. A reply whose content is a list of parts is now
read (`content()` is `null`, `contentParts()` holds them) instead of failing to
parse.

## [1.2.0] — 2026-11-26

BREAKING: framework state keys are now `shashu_session_id` / `shashu_trace_id`.
The previous names are not read.

Provider pass-through errors are recognised by `SHASHU Provider Error:` (needs a
gateway that sends it). There is no fallback to the previous prefix.

SHASHU keys start `shashu_sk_`. Metadata, docs, and package URLs no longer
carry the previous product name. Java request extras use `shashuMetadata` /
`shashuHeaders` (the previous method names are gone).

Fixed: after a request has been sent, a dropped connection is
no longer retried automatically (avoids running the same request twice).

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

- Environment variables are `SHASHU_API_KEY` / `SHASHU_BASE_URL` / `SHASHU_DISABLE` only. The old environment-variable names are no longer read.
- Wire names are SHASHU only. Old wire names are neither sent nor read.

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
