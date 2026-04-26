package io.appfirewall.core.ip;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudflareRangeRegistryTest {

    @Test
    void bakedSnapshotRecognizesKnownCfIps() throws Exception {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry();
        // 104.16.0.0/13 → any 104.16.*.* is CF.
        assertTrue(r.isCloudflare(InetAddress.getByName("104.16.0.1")));
        assertTrue(r.isCloudflare("172.64.0.5"));
        // 2606:4700::/32 → CF.
        assertTrue(r.isCloudflare(InetAddress.getByName("2606:4700:10::1")));
    }

    @Test
    void bakedSnapshotRejectsNonCfIps() {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry();
        assertFalse(r.isCloudflare("8.8.8.8"));
        assertFalse(r.isCloudflare("1.1.1.1"));  // owned by CF but not in published edge ranges
        assertFalse(r.isCloudflare("not-an-ip"));
        assertFalse(r.isCloudflare((String) null));
    }

    @Test
    void refreshAdoptsValidResponse() throws Exception {
        String body = "{\"result\":{\"ipv4_cidrs\":[\"10.0.0.0/8\"],\"ipv6_cidrs\":[\"fe80::/10\"]},"
                + "\"success\":true}";
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> Optional.of(body));
        r.refreshOnce();
        // Adopted: 10.0.0.5 is CF, 104.16.0.1 isn't (no longer in the list).
        assertTrue(r.isCloudflare(InetAddress.getByName("10.0.0.5")));
        assertFalse(r.isCloudflare(InetAddress.getByName("104.16.0.1")));
        assertTrue(r.lastRefreshMillis() > 0);
    }

    @Test
    void refreshKeepsSnapshotOnEmptyResponse() {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> Optional.empty());
        r.refreshOnce();
        // Baked snapshot still serves.
        assertTrue(r.isCloudflare("104.16.0.1"));
        assertEquals(0L, r.lastRefreshMillis());
    }

    @Test
    void refreshKeepsSnapshotOnGarbageResponse() {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> Optional.of("not json"));
        r.refreshOnce();
        assertTrue(r.isCloudflare("104.16.0.1"));
    }

    @Test
    void refreshKeepsSnapshotOnEmptyArrays() {
        String body = "{\"result\":{\"ipv4_cidrs\":[],\"ipv6_cidrs\":[]}}";
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> Optional.of(body));
        r.refreshOnce();
        // We refuse to adopt empty lists; baked snapshot still serves.
        assertTrue(r.isCloudflare("104.16.0.1"));
    }

    @Test
    void refreshSwallowsFetcherException() {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> {
            throw new RuntimeException("network down");
        });
        // Must not throw.
        r.refreshOnce();
        assertTrue(r.isCloudflare("104.16.0.1"));
    }

    @Test
    void extractStringArrayParsesArrays() {
        String json = "{\"result\":{\"ipv4_cidrs\":[\"10.0.0.0/8\",\"192.168.0.0/16\"],"
                + "\"ipv6_cidrs\":[\"::1/128\"]}}";
        List<String> v4 = CloudflareRangeRegistry.extractStringArray(json, "ipv4_cidrs");
        List<String> v6 = CloudflareRangeRegistry.extractStringArray(json, "ipv6_cidrs");
        assertEquals(List.of("10.0.0.0/8", "192.168.0.0/16"), v4);
        assertEquals(List.of("::1/128"), v6);
    }

    @Test
    void extractStringArrayHandlesMissingKey() {
        assertEquals(List.of(),
                CloudflareRangeRegistry.extractStringArray("{}", "ipv4_cidrs"));
        assertEquals(List.of(),
                CloudflareRangeRegistry.extractStringArray(null, "ipv4_cidrs"));
    }

    @Test
    void startAndStopAreIdempotent() {
        CloudflareRangeRegistry r = new CloudflareRangeRegistry(url -> Optional.empty());
        r.start();
        r.start();  // idempotent
        r.stop();
        r.stop();   // idempotent
    }
}
