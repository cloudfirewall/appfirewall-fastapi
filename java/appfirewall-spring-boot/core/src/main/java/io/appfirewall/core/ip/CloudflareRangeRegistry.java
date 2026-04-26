package io.appfirewall.core.ip;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cloudflare IP range registry.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_cf_ranges.py}.
 * Ships a baked snapshot of Cloudflare's published IPv4 and IPv6 ranges so
 * the SDK works offline and without warm-up. The {@link #start()} method
 * schedules a background refresh from
 * {@code https://api.cloudflare.com/client/v4/ips} every 24 hours; if the
 * refresh fails the baked snapshot keeps serving (fail-soft).
 *
 * <p>{@link #isCloudflare(InetAddress)} is the hot-path entry point. The CF
 * list has roughly 22 ranges, so a linear scan is fine; no trie needed.
 *
 * <p>Baked snapshot date: 2026-04-22 (matches the FastAPI baked snapshot).
 */
public final class CloudflareRangeRegistry {

    private static final Logger LOG = Logger.getLogger("appfirewall");

    private static final String CF_IPS_URL = "https://api.cloudflare.com/client/v4/ips";
    private static final long REFRESH_INTERVAL_HOURS = 24L;
    private static final long INITIAL_DELAY_SECONDS = 5L;
    private static final long FETCH_TIMEOUT_SECONDS = 5L;

    private static final List<String> BAKED_V4 = List.of(
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "141.101.64.0/18",
            "108.162.192.0/18",
            "190.93.240.0/20",
            "188.114.96.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "162.158.0.0/15",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "131.0.72.0/22"
    );

    private static final List<String> BAKED_V6 = List.of(
            "2400:cb00::/32",
            "2606:4700::/32",
            "2803:f800::/32",
            "2405:b500::/32",
            "2405:8100::/32",
            "2a06:98c0::/29",
            "2c0f:f248::/32"
    );

    /** Test seam: HTTP fetcher contract used by the refresh loop. */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * Fetch the response body for the given URL. Returns empty on error.
         * Implementations must not throw.
         */
        Optional<String> get(String url);
    }

    private volatile List<CidrBlock> v4;
    private volatile List<CidrBlock> v6;
    private volatile long lastRefreshMillis;

    private final Fetcher fetcher;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public CloudflareRangeRegistry() {
        this(defaultFetcher());
    }

    /** Test-friendly ctor accepting a custom fetcher. */
    public CloudflareRangeRegistry(Fetcher fetcher) {
        this.fetcher = fetcher;
        this.v4 = parseAll(BAKED_V4);
        this.v6 = parseAll(BAKED_V6);
    }

    public boolean isCloudflare(InetAddress addr) {
        if (addr == null) return false;
        List<CidrBlock> blocks = (addr instanceof Inet6Address) ? v6 : v4;
        for (CidrBlock b : blocks) {
            if (b.contains(addr)) return true;
        }
        return false;
    }

    public boolean isCloudflare(String ipLiteral) {
        Optional<InetAddress> addr = IpLiteralParser.parse(ipLiteral);
        return addr.isPresent() && isCloudflare(addr.get());
    }

    /** Schedule the 24-hour background refresh. Idempotent. */
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "appfirewall-cf-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::refreshOnce,
                INITIAL_DELAY_SECONDS,
                REFRESH_INTERVAL_HOURS * 3600L,
                TimeUnit.SECONDS
        );
    }

    /** Cancel the background task and shut down the scheduler. */
    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
    }

    public long lastRefreshMillis() { return lastRefreshMillis; }

    /**
     * Run a single refresh. Exposed package-private for tests.
     * Never throws &mdash; failures are caught and logged at INFO once per
     * outage window.
     */
    void refreshOnce() {
        try {
            Optional<String> body = fetcher.get(CF_IPS_URL);
            if (body.isEmpty()) {
                logFailureOnce("empty response");
                return;
            }
            List<String> rawV4 = extractStringArray(body.get(), "ipv4_cidrs");
            List<String> rawV6 = extractStringArray(body.get(), "ipv6_cidrs");

            // Only adopt if BOTH lists came back non-empty and parse cleanly.
            // An empty list could mean an upstream hiccup; keep the existing
            // snapshot rather than zero-out detection.
            if (rawV4.isEmpty() || rawV6.isEmpty()) {
                logFailureOnce("response missing v4 or v6 array");
                return;
            }
            List<CidrBlock> parsedV4 = parseAll(rawV4);
            List<CidrBlock> parsedV6 = parseAll(rawV6);
            if (parsedV4.isEmpty() || parsedV6.isEmpty()) {
                logFailureOnce("response had no parseable CIDRs");
                return;
            }

            this.v4 = parsedV4;
            this.v6 = parsedV6;
            this.lastRefreshMillis = System.currentTimeMillis();
            refreshFailureLogged.set(false);
        } catch (Throwable t) {
            // Defense in depth: never let a refresh kill the scheduler thread.
            logFailureOnce(t.getMessage());
        }
    }

    private void logFailureOnce(String reason) {
        if (refreshFailureLogged.compareAndSet(false, true)) {
            LOG.log(Level.INFO, "appfirewall: CF range refresh failed ({0}); "
                    + "keeping baked snapshot", reason);
        }
    }

    private static List<CidrBlock> parseAll(List<String> cidrs) {
        return cidrs.stream()
                .map(CidrBlock::parse)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    /**
     * Targeted extractor: finds the array bound to {@code key} and returns
     * the JSON-string elements inside it. Robust enough for the Cloudflare
     * response shape (stable, well-formed). We deliberately don't pull in
     * Jackson here &mdash; the {@code core} module ships with no JSON-lib
     * runtime dep (spec §10).
     */
    static List<String> extractStringArray(String json, String key) {
        if (json == null) return List.of();
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return List.of();
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) return List.of();
        int open = json.indexOf('[', colon);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0 || close <= open) return List.of();
        String slice = json.substring(open + 1, close);

        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < slice.length()) {
            int start = slice.indexOf('"', i);
            if (start < 0) break;
            int end = slice.indexOf('"', start + 1);
            if (end < 0) break;
            out.add(slice.substring(start + 1, end));
            i = end + 1;
        }
        return out;
    }

    private static Fetcher defaultFetcher() {
        return url -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                        .header("Accept", "application/json")
                        .header("User-Agent", "appfirewall-spring-boot")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    return Optional.empty();
                }
                return Optional.of(resp.body());
            } catch (Throwable t) {
                return Optional.empty();
            }
        };
    }
}
