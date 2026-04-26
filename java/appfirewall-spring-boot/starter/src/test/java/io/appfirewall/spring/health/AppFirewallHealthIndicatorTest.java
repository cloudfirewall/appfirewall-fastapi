package io.appfirewall.spring.health;

import io.appfirewall.core.Client;
import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.buffer.EventBuffer;
import io.appfirewall.core.buffer.Shipper;
import io.appfirewall.core.config.ClientConfig;
import io.appfirewall.core.config.Mode;
import io.appfirewall.core.ip.CloudflareRangeRegistry;
import io.appfirewall.core.ip.IpResolver;
import io.appfirewall.core.ip.TrustedProxyConfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppFirewallHealthIndicatorTest {

    @Test
    void upWhenBreakerClosed() {
        Client c = newClient();
        Health h = new AppFirewallHealthIndicator(c).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("breaker", "CLOSED");
        assertThat(h.getDetails()).containsKey("buffer_size");
        assertThat(h.getDetails()).containsKey("buffer_capacity");
    }

    @Test
    void outOfServiceWhenBreakerOpen() {
        Client c = newClient();
        // Trip the breaker.
        for (int i = 0; i < 10; i++) c.breaker().recordFailure();
        Health h = new AppFirewallHealthIndicator(c).health();
        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("breaker", "OPEN");
    }

    @Test
    void neverDown() {
        // We deliberately don't expose a path that surfaces as Status.DOWN —
        // a customer outage must not flip the customer's pod.
        Client c = newClient();
        for (int i = 0; i < 100; i++) c.breaker().recordFailure();
        Health h = new AppFirewallHealthIndicator(c).health();
        assertThat(h.getStatus()).isNotEqualTo(Status.DOWN);
    }

    private Client newClient() {
        ClientConfig config = new ClientConfig(
                "test-key",
                "http://localhost",
                "test",
                Mode.OFF,
                null,
                List.of(),
                true,
                Map.of(),
                false,
                ClientConfig.OnError.IGNORE
        );
        CloudflareRangeRegistry cf = new CloudflareRangeRegistry();
        IpResolver ip = new IpResolver(cf, new TrustedProxyConfig(List.of()));
        EventBuffer buf = new EventBuffer();
        CircuitBreaker breaker = new CircuitBreaker(5, 30_000);
        Shipper shipper = new Shipper(buf, breaker, Mode.OFF, "http://localhost", "", null);
        return new Client(config, cf, ip, Map.of(), buf, breaker, shipper);
    }
}
