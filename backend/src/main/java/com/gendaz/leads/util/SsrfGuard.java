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
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                return false;
            }
            String ip = address.getHostAddress();
            if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.16.")
                    || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                    || ip.startsWith("172.2") || ip.startsWith("172.3") || ip.startsWith("127.")
                    || ip.startsWith("169.254.") || ip.startsWith("::1") || ip.equals("0.0.0.0")) {
                return false;
            }
            return true;
        } catch (RuntimeException | java.net.UnknownHostException e) {
            return false;
        }
    }
}
