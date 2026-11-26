package com.pulseagent.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * SSRF protection for every URL a workspace configures (Prometheus, Loki, Tempo, Grafana,
 * webhooks, AI base URLs). Requires https and refuses hosts that resolve to loopback, private,
 * link-local (including cloud metadata), carrier-grade NAT, multicast or unspecified addresses.
 * Checked when settings are saved and again before every request (DNS can change).
 */
public final class OutboundGuard {

    private OutboundGuard() {}

    public static URI check(String url, boolean allowPrivate) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("URL is empty");
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPrivate && scheme.equals("http"))) {
            throw new IllegalArgumentException("URL must use https");
        }
        if (u.getRawUserInfo() != null) throw new IllegalArgumentException("Put credentials in the token field, not in the URL");
        if (u.getHost() == null || u.getHost().isBlank()) throw new IllegalArgumentException("URL has no host");
        if (u.getRawQuery() != null || u.getRawFragment() != null) throw new IllegalArgumentException("URL must not contain a query or fragment");
        if (allowPrivate) return u;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local")) {
            throw new IllegalArgumentException("Private hosts are not allowed");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (isPrivate(a)) throw new IllegalArgumentException("That host resolves to a private network address");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Host could not be resolved");
        }
        return u;
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 240;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true; // unique local fc00::/7
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return false;
    }
}
