# appfirewall-spring-boot

Origin-side abuse-signal middleware for Spring Boot apps behind Cloudflare.
Sibling of [`appfirewall-fastapi`](../../python/appfirewall-fastapi/) in the
[`appfirewall-sdk`](../../) monorepo.

> **Status:** v0.1 feature-complete. Servlet **and** reactive filters,
> Actuator health, Micrometer metrics, the 24-hour Cloudflare-range
> refresh, and the Maven Central publish pipeline are all wired. Pending
> the first release: Sonatype namespace registration for `io.appfirewall`
> and a decision on the open spec questions.

## Reading order

1. [docs/specs/appfirewall-spring-boot.md](../../docs/specs/appfirewall-spring-boot.md)
   &mdash; the full design contract for this SDK.
2. [`python/appfirewall-fastapi/`](../../python/appfirewall-fastapi/) &mdash;
   the reference implementation. When behaviour here is ambiguous, the
   Python SDK is the source of truth.
3. [`AGENTS.md`](../../AGENTS.md) at the repo root &mdash; cross-SDK
   conventions and the Golden Rules (fail open, never block the request
   path, etc.).

## Modules

```
java/appfirewall-spring-boot/
├── settings.gradle.kts
├── build.gradle.kts            ← root: toolchain, lint, test setup
├── core/                       ← pure Java, no Spring
│   └── src/main/java/io/appfirewall/core/
│       ├── classifier/         ← PathClassifier, Classification (✅ ported)
│       ├── breaker/            ← CircuitBreaker (✅ ported)
│       ├── ratelimit/          ← SlidingWindowLimiter (stub)
│       ├── ip/                 ← IpResolver, CloudflareRangeRegistry (stubs)
│       ├── buffer/             ← EventBuffer, Shipper (stubs)
│       ├── context/            ← RequestContext (✅ ported)
│       ├── config/             ← ClientConfig record, Mode enum (✅ done)
│       └── Client.java         ← coordinator (stub)
└── starter/                    ← Spring Boot autoconfig
    └── src/main/java/io/appfirewall/spring/
        ├── AppFirewall.java                 ← static facade (skeleton)
        ├── AppFirewallProperties.java       ← @ConfigurationProperties POJO
        ├── AppFirewallAutoConfiguration.java
        ├── servlet/AppFirewallFilter.java
        ├── reactive/AppFirewallWebFilter.java
        ├── context/RequestContextHolder.java
        └── health/AppFirewallHealthIndicator.java
```

## Build

Java 17 toolchain via Gradle:

```bash
cd java/appfirewall-spring-boot
./gradlew test
./gradlew :core:test     # just the pure-logic tests
./gradlew build
```

(Gradle wrapper is not committed yet &mdash; run `gradle wrapper --gradle-version 8.10`
once locally to generate it, or use a system-installed `gradle 8.x`.)

## What's ported, what's next

| Component | Status | Notes |
|---|---|---|
| `Classification` enum | ✅ | wire-compatible string values |
| `PathClassifier` | ✅ | full port + parametrized tests mirroring Python |
| `CircuitBreaker` | ✅ | full port + tests with injectable `Clock` |
| `RequestContext` | ✅ | value class; mutable `status` + customFields |
| `ClientConfig` / `Mode` | ✅ | record + enum |
| `IpResolver` | ✅ | full port + every case from `test_ip.py` |
| `CloudflareRangeRegistry` | ✅ | baked snapshot; 24 h refresh still TODO |
| `SlidingWindowLimiter` | ✅ | full port + tests with injectable time source |
| `EventBuffer` | ✅ | drop-oldest, bounded, non-blocking emit |
| `Shipper` | ✅ | dedicated thread, gzip NDJSON, breaker, WARN-on-fail |
| `JsonEncoder` (internal) | ✅ | hand-rolled; no JSON-lib runtime dep |
| `Client` coordinator | ✅ | wires subsystems + builds event maps |
| `RequestContextHolder` | ✅ | strategy facade + servlet `ThreadLocal` impl |
| `AppFirewall` static facade | ✅ | wired to `Client` via autoconfig |
| `AppFirewallFilter` (servlet) | ✅ | `OncePerRequestFilter`; fail-open contract |
| `AppFirewallProperties` | ✅ | `@ConfigurationProperties("appfirewall")` |
| `AppFirewallAutoConfiguration` | ✅ | bean wiring + filter ordering |
| End-to-end `@SpringBootTest` | ✅ | drives traffic, asserts NDJSON output |
| `AppFirewallWebFilter` (reactive) | ✅ | `WebFilter`; HTTP events; v0.1 caveat on `record()` in operators |
| `AppFirewallHealthIndicator` | ✅ | UP / OUT_OF_SERVICE; never DOWN |
| `AppFirewallMetrics` (Micrometer) | ✅ | gauges for buffer + last-ship status |
| CF ranges 24 h refresh | ✅ | scheduled executor; fetcher seam for tests |
| Maven Central publish workflow | ✅ | tag-prefix gated; Sonatype + GPG via secrets |

Pre-release tasks (not v0.1 code work):

1. Register the `io.appfirewall` namespace at
   [Sonatype Central](https://central.sonatype.com/) and verify ownership.
2. Add the GitHub repository secrets used by the publish workflow:
   `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`.
3. Create a `maven-central` GitHub environment with required reviewers so
   each release goes through a manual gate.
4. Decide the spec's Reactor-`Context`-propagation question (open question
   1) &mdash; ship a `Mono`-returning `AppFirewall.recordMono(...)` in
   v0.1+ vs. wait for `io.micrometer:context-propagation` adoption.

To cut the first release once the above is done: tag
`java-spring-boot-vX.Y.Z`, publish a GitHub Release. The
`java-spring-boot-publish.yml` workflow will run tests, build, sign, and
publish to Maven Central.

## License

Apache-2.0.
