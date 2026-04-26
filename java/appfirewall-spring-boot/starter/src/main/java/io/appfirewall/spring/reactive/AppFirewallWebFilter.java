package io.appfirewall.spring.reactive;

import io.appfirewall.core.Client;
import io.appfirewall.core.classifier.Classification;
import io.appfirewall.core.context.RequestContext;
import io.appfirewall.core.ip.CfMetadata;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reactive ({@code WebFlux}) sibling of
 * {@link io.appfirewall.spring.servlet.AppFirewallFilter}.
 *
 * <p>Records the HTTP event after the chain completes, regardless of
 * success or failure. Always fail-open: any internal {@link Throwable} is
 * caught at FINE; the request continues.
 *
 * <p><b>v0.1 caveat</b> (spec §6.4): the synchronous
 * {@code AppFirewall.record(...)} facade does <em>not</em> work from inside
 * a {@code Mono}/{@code Flux} operator on this stack. Reactor
 * {@code Context} can only be read inside an operator, but the static
 * facade reads its holder synchronously. v0.1+ will introduce a
 * {@code Mono}-returning helper that uses {@code deferContextual} to read
 * the context properly. For now, custom events captured automatically by
 * this filter are emitted as-is (HTTP events only); per-handler signals
 * via {@code AppFirewall.record} are a servlet-only feature in v0.1.
 */
public final class AppFirewallWebFilter implements WebFilter {

    private static final Logger LOG = Logger.getLogger("appfirewall");

    private final Client client;

    public AppFirewallWebFilter(Client client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        final RequestContext ctx;
        try {
            ctx = buildContext(exchange);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "appfirewall: context build failed", t);
            return chain.filter(exchange);
        }

        return chain.filter(exchange).doFinally(signal -> {
            try {
                HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                int status = statusCode == null ? 0 : statusCode.value();
                if (status == 0 && signal != reactor.core.publisher.SignalType.ON_COMPLETE) {
                    // No status sent and the chain ended exceptionally → 500.
                    status = 500;
                }
                ctx.setStatus(status);
                postResponse(ctx);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "appfirewall: post-response hook failed", t);
            }
        });
    }

    private RequestContext buildContext(ServerWebExchange exchange) {
        ServerHttpRequest req = exchange.getRequest();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> e : req.getHeaders().entrySet()) {
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) continue;
            headers.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), values.get(0));
        }

        InetSocketAddress remote = req.getRemoteAddress();
        String peer = remote == null ? null : remote.getAddress().getHostAddress();

        String ip = client.ipResolver().resolveClientIp(headers, peer);
        CfMetadata cf = client.extractCfMetadata(headers, peer);
        Map<String, String> cfMap = cf.isEmpty() ? null : flatten(cf);

        String method = req.getMethod() == null ? "GET" : req.getMethod().name();
        String path = req.getPath().value();
        if (path == null || path.isEmpty()) path = "/";

        return new RequestContext(
                method,
                path,
                ip,
                firstHeader(req.getHeaders(), HttpHeaders.USER_AGENT),
                cfMap,
                System.nanoTime()
        );
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static Map<String, String> flatten(CfMetadata cf) {
        Map<String, String> m = new LinkedHashMap<>();
        if (cf.country() != null) m.put("country", cf.country());
        if (cf.ray() != null) m.put("ray", cf.ray());
        if (cf.asn() != null) m.put("asn", cf.asn());
        return m;
    }

    private void postResponse(RequestContext ctx) {
        Classification classification = null;
        if (ctx.status() == 404 && client.config().classify404()) {
            classification = client.classifyPath(ctx.path());
            client.rateLimited(ctx.ip(), classification.wire());
        }
        client.recordHttpEvent(ctx, classification);
    }
}
