package com.maydings.vibelauncher;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wireless debugging's ports, which change each time it's turned on: the TV announces them on the local network
 * (mDNS), "_adb-tls-pairing._tcp" while its pairing window is open and "_adb-tls-connect._tcp" while Wireless
 * debugging is on. Other phones and TVs on the network announce theirs too, so only a service at one of this TV's
 * own addresses counts. Worker thread: blocks until found or the time is up.
 */
final class AdbMdns {

    static final String PAIRING = "_adb-tls-pairing._tcp", CONNECT = "_adb-tls-connect._tcp";

    private AdbMdns() {
    }

    /** This TV's port for the service, or -1. */
    static int find(Context context, String type, long timeoutMs) {
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;
        Set<InetAddress> mine = ownAddresses();
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch found = new CountDownLatch(1);
        // Resolving is one at a time on older Androids (a second at once fails): a queue.
        Queue<NsdServiceInfo> toResolve = new ArrayDeque<>();
        boolean[] resolving = {false};
        Object lock = new Object();
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            NsdServiceInfo s;
            synchronized (lock) {
                if (resolving[0] || found.getCount() == 0) return;
                s = toResolve.poll();
                if (s == null) return;
                resolving[0] = true;
            }
            try {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int error) {
                        done();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        InetAddress host = info.getHost();
                        android.util.Log.i("VibeAdb", "mDNS " + info.getServiceName() + " at " + host + ":" + info.getPort()
                                + " mine=" + (host != null && mine.contains(host)));
                        if (host != null && (mine.contains(host) || host.isLoopbackAddress()) && info.getPort() > 0) {
                            port.set(info.getPort());
                            found.countDown();
                        }
                        done();
                    }

                    private void done() {
                        synchronized (lock) {
                            resolving[0] = false;
                        }
                        next[0].run();
                    }
                });
            } catch (RuntimeException e) {
                synchronized (lock) {
                    resolving[0] = false;
                }
            }
        };
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (lock) {
                    toResolve.add(info);
                }
                next[0].run();
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                android.util.Log.i("VibeAdb", "mDNS discovery failed " + errorCode);
                found.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            return -1;
        }
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        return port.get();
    }

    private static Set<InetAddress> ownAddresses() {
        Set<InetAddress> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                out.addAll(Collections.list(ni.getInetAddresses()));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
