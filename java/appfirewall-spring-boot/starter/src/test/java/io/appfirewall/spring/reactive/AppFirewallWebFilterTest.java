package io.appfirewall.spring.reactive;

import io.appfirewall.core.Client;
import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.buffer.EventBuffer;
import io.appfirewall.core.buffer.Shipper;
import io.appfirewall.core.config.ClientConfig;
import io.appfirewall.core.config.Mode;
import io.appfirewall.core.ip.CloudflareRangeRegistry;
import io.appfirewall.core.ip.IpResolver;
import io.appfirewall.core.ip.TrustedProxyConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level test of the reactive {@link AppFirewallWebFilter}. Drives the
 * filter directly with {@link MockServerWebExchange} so we don't need to
 * boot a real reactive container alongside the servlet test.
 */
class AppFirewallWebFilterTest {

    private Client client;
    private AppFirewallWebFilter filter;

    @BeforeEach
    void setUp() {
        // Mode.LOCAL with no started shipper: buffer accumulates events,
        // tests drain them directly without any disk or HTTP I/O.
        ClientConfig config = new ClientConfig(
                "k", "http://localhost", "test",
                Mode.LOCAL, null, List.of(), true,
                Map.of(), false, ClientConfig.OnError.IGNORE
        );
        CloudflareRangeRegistry cf = new CloudflareRangeRegistry();
        IpResolver ip = new IpResolver(cf, new TrustedProxyConfig(List.of()));
        EventBuffer buf = new EventBuffer();
        CircuitBreaker breaker = new CircuitBreaker(5, 30_000);
        Shipper ship = new Shipper(buf, breaker, Mode.LOCAL, "http://localhost", "", null);
        client = new Client(config, cf, ip, Map.of(), buf, breaker, ship);
        filter = new AppFirewallWebFilter(client);
    }

    @Test
    void emitsHttpEventOnSuccess() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/healthz").build()
        );
        ex.getResponse().setStatusCode(HttpStatus.OK);

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(client.buffer().size()).isEqualTo(1);
        Map<String, Object> evt = pollOne();
        assertThat(evt).containsEntry("event", "http")
                .containsEntry("path", "/healthz")
                .containsEntry("method", "GET");
        assertThat((int) evt.get("status")).isEqualTo(200);
    }

    @Test
    void classifiesScannerOn404() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/wp-admin").build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        ex.getResponse().setStatusCode(HttpStatus.NOT_FOUND);

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        Map<String, Object> evt = pollOne();
        assertThat(evt).containsEntry("path", "/wp-admin")
                .containsEntry("classification", "scanner");
    }

    @Test
    void classifiesBenignOn404() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/favicon.ico").build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        ex.getResponse().setStatusCode(HttpStatus.NOT_FOUND);

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        Map<String, Object> evt = pollOne();
        assertThat(evt).containsEntry("classification", "benign-miss");
    }

    @Test
    void emitsEventEvenOnInnerError() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/boom").build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);

        StepVerifier.create(
                filter.filter(ex, e -> Mono.error(new RuntimeException("kaboom")))
        ).verifyError(RuntimeException.class);

        // Status defaulted to 500 since no real response was sent.
        Map<String, Object> evt = pollOne();
        assertThat(evt).containsEntry("path", "/boom");
        assertThat((int) evt.get("status")).isEqualTo(500);
    }

    private Map<String, Object> pollOne() {
        java.util.List<Map<String, Object>> drained = new java.util.ArrayList<>();
        client.buffer().drainTo(drained, 1);
        assertThat(drained).hasSize(1);
        return drained.get(0);
    }
}
