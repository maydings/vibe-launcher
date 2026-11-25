package com.maydings.vibelauncher;

import org.junit.Test;

import static org.junit.Assert.*;

/** Hostname validation in AdBlock.clean(). */
public class AdBlockTest {

    @Test
    public void acceptsValidHostnamesAndStripsUrls() {
        assertEquals("dns.adguard-dns.com", AdBlock.clean("https://DNS.AdGuard-DNS.com/dns-query"));
        assertEquals("abc123.dns.nextdns.io", AdBlock.clean("tls://abc123.dns.nextdns.io:853."));
        assertEquals("dns.xn--bcher-kva.example", AdBlock.clean("dns.xn--bcher-kva.example"));
    }

    @Test
    public void rejectsIpv4InvalidTldsAndOversizedHosts() {
        assertEquals("", AdBlock.clean("1.1.1.10"));
        assertEquals("", AdBlock.clean("192.168.1.12"));
        assertEquals("", AdBlock.clean("foo.--"));
        assertEquals("", AdBlock.clean("foo.-bar"));
        assertEquals("", AdBlock.clean("localhost"));
        StringBuilder longHost = new StringBuilder();
        for (int i = 0; i < 45; i++) longHost.append("abcdef.");
        longHost.append("com");
        assertEquals("", AdBlock.clean(longHost.toString()));
    }
}
