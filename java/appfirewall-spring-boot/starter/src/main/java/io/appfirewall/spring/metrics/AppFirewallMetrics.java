package io.appfirewall.spring.metrics;

import io.appfirewall.core.Client;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer counters/gauges for the SDK. See spec §4.3.
 *
 * <p>Registered as a bean conditional on {@link MeterRegistry} being on the
 * classpath. v0.1 wires the gauges that come for free off the {@code Client}
 * (buffer occupancy, drop counters, last-ship status). The flush-outcome
 * counters and the batch-size distribution summary will be wired in a
 * follow-up once the {@code Shipper} reports them per call.
 */
public final class AppFirewallMetrics {

    public AppFirewallMetrics(Client client, MeterRegistry registry) {
        registry.gauge("appfirewall.buffer.size", client, c -> c.buffer().size());
        registry.gauge("appfirewall.buffer.capacity", client, c -> c.buffer().capacity());
        registry.gauge("appfirewall.events.emitted", client, c -> c.buffer().emittedCount());
        registry.gauge("appfirewall.events.dropped.overflow", client, c -> c.buffer().droppedOverflowCount());
        registry.gauge("appfirewall.last_ship_status", client,
                c -> c.shipper() == null ? 0 : c.shipper().lastShipStatus());
    }
}
