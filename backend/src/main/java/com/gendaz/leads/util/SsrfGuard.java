package com.gendaz.leads.util;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class SsrfGuard {

    public boolean isSafe(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) return false;
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || host.endsWith(".internal")) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | java.net.UnknownHostException e) {
            return false;
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()
                || address.isAnyLocalAddress()) {
            return false;
        }
        if (address instanceof java.net.Inet4Address) {
            byte[] bytes = address.getAddress();
            return isPublicIpv4(bytes);
        } else if (address instanceof java.net.Inet6Address) {
            return isPublicIpv6(address);
        }
        return false;
    }

    private boolean isPublicIpv4(byte[] b) {
        int a = Byte.toUnsignedInt(b[0]);
        int second = Byte.toUnsignedInt(b[1]);

        if (a == 0) return false;
        if (a == 10) return false;
        if (a == 127) return false;

        if (a == 100 && second >= 64 && second <= 127) {
            return false;
        }

        if (a == 169 && second == 254) {
            return false;
        }

        if (a == 172 && second >= 16 && second <= 31) {
            return false;
        }

        if (a == 192 && second == 168) {
            return false;
        }

        if (a >= 224) {
            return false;
        }

        return true;
    }

    private boolean isPublicIpv6(InetAddress address) {
        byte[] b = address.getAddress();

        if (b.length != 16) {
            return false;
        }

        boolean loopback = true;
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                loopback = false;
                break;
            }
        }

        if (loopback && b[15] == 1) {
            return false;
        }

        int first = Byte.toUnsignedInt(b[0]);
        int second = Byte.toUnsignedInt(b[1]);

        // fc00::/7
        if ((first & 0xFE) == 0xFC) {
            return false;
        }

        // fe80::/10
        if (first == 0xFE && (second & 0xC0) == 0x80) {
            return false;
        }

        return true;
    }
}
