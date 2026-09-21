package com.gendaz.leads.util;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    void ipv4_0_0_0_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://0.0.0.1"));
    }

    @Test
    void ipv4_10_0_0_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://10.0.0.1"));
    }

    @Test
    void ipv4_100_64_0_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://100.64.0.1"));
    }

    @Test
    void ipv4_100_127_255_254_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://100.127.255.254"));
    }

    @Test
    void ipv4_127_0_0_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://127.0.0.1"));
    }

    @Test
    void ipv4_169_254_169_254_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://169.254.169.254"));
    }

    @Test
    void ipv4_172_16_0_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://172.16.0.1"));
    }

    @Test
    void ipv4_172_31_255_254_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://172.31.255.254"));
    }

    @Test
    void ipv4_192_168_1_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://192.168.1.1"));
    }

    @Test
    void ipv6_loopback_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://[::1]"));
    }

    @Test
    void ipv6_fc00_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://[fc00::1]"));
    }

    @Test
    void ipv6_fd12_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://[fd12::1]"));
    }

    @Test
    void ipv6_fe80_1_false() throws UnknownHostException {
        assertFalse(guard.isSafe("http://[fe80::1]"));
    }

    @Test
    void publicIpv4_true() throws UnknownHostException {
        assertTrue(guard.isSafe("http://8.8.8.8"));
    }

    @Test
    void publicIpv6_true() throws UnknownHostException {
        assertTrue(guard.isSafe("http://[2001:4860:4860::8888]"));
    }

    @Test
    void multiIpDnsRejectsIfAnyPrivate() throws UnknownHostException {
        InetAddress publicAddr = Inet4Address.getByAddress(new byte[]{(byte)8, (byte)8, (byte)8, (byte)8});
        InetAddress privateAddr = Inet4Address.getByAddress(new byte[]{(byte)10, (byte)0, (byte)0, (byte)1});

        assertTrue(invokeIsPublic(publicAddr));
        assertFalse(invokeIsPublic(privateAddr));
    }

    private boolean invokeIsPublic(InetAddress address) {
        try {
            var method = SsrfGuard.class.getDeclaredMethod("isPublic", InetAddress.class);
            method.setAccessible(true);
            return (boolean) method.invoke(guard, address);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}