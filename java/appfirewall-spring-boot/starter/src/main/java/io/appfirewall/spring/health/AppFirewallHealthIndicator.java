package io.appfirewall.spring.health;

import io.appfirewall.core.Client;
import io.appfirewall.core.breaker.BreakerState;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;

/**
 * Spring Boot Actuator health indicator.
 *
 * <p>Status is {@link Status#UP} when the shipper's circuit breaker is
 * CLOSED or HALF_OPEN, {@link Status#OUT_OF_SERVICE} when OPEN.
 * <b>Never {@link Status#DOWN}</b> &mdash; our outage must not flip the
 * customer's pod (spec §4.3).
 */
public final class AppFirewallHealthIndicator implements HealthIndicator {

    private final Client client;

    public AppFirewallHealthIndicator(Client client) {
        this.client = client;
    }

    @Override
    public Health health() {
        BreakerState state = client.breaker().state();
        Status status = (state == BreakerState.OPEN) ? Status.OUT_OF_SERVICE : Status.UP;

        Health.Builder b = Health.status(status)
                .withDetail("mode", client.config().mode().name())
                .withDetail("breaker", state.name())
                .withDetail("buffer_size", client.buffer().size())
                .withDetail("buffer_capacity", client.buffer().capacity())
                .withDetail("events_emitted", client.buffer().emittedCount())
                .withDetail("events_dropped_overflow", client.buffer().droppedOverflowCount())
                .withDetail("last_ship_status", client.shipper() == null ? 0 : client.shipper().lastShipStatus());

        long lastShip = client.shipper() == null ? 0L : client.shipper().lastShipMillis();
        if (lastShip > 0) {
            b.withDetail("last_ship_at", Instant.ofEpochMilli(lastShip).toString());
        }
        return b.build();
    }
}
