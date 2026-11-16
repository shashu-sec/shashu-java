# Changelog — SHASHU Java SDK

This project adheres to [Semantic Versioning](https://semver.org/).

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
