package com.viral.shortsmaker.core;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/** Shared HTTP clients (one pool for the whole app instead of per-activity clients). */
public final class Net {
    private static final ConnectionPool POOL = new ConnectionPool(8, 5, TimeUnit.MINUTES);
    private static OkHttpClient client, fast, ai;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Net() {}

    public static synchronized OkHttpClient client() {
        if (client == null) client = new OkHttpClient.Builder().connectionPool(POOL)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build();
        return client;
    }

    /**
     * v8: parallel range downloads. HTTP/1.1 only, own pool: with HTTP/2 OkHttp put all 8 "parallel" ranges on ONE
     * multiplexed connection to googlevideo, so YouTube's per-connection throttle applied to all of them together
     * (v7 on Sakib's phone: 8 ranges, 0.8 MB/s total).
     */
    private static OkHttpClient download;
    public static synchronized OkHttpClient download() {
        if (download == null) download = new OkHttpClient.Builder().connectionPool(new ConnectionPool(16, 2, TimeUnit.MINUTES))
                .protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
        return download;
    }

    /** For Groq/Gemini: long uploads and slow model answers. */
    public static synchronized OkHttpClient ai() {
        if (ai == null) ai = client().newBuilder().writeTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build();
        return ai;
    }

    public static synchronized OkHttpClient fast() {
        if (fast == null) fast = client().newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
        return fast;
    }

    /** v14: same as fast() but IPv4 only (YouTube's bot wall is per address; the other family often passes) */
    private static OkHttpClient ipv4;
    public static synchronized OkHttpClient ipv4() {
        if (ipv4 == null) ipv4 = fast().newBuilder().connectionPool(new ConnectionPool(2, 1, TimeUnit.MINUTES)).dns(host -> {
            java.util.List<java.net.InetAddress> all = okhttp3.Dns.SYSTEM.lookup(host), v4 = new java.util.ArrayList<>();
            for (java.net.InetAddress a : all) if (a instanceof java.net.Inet4Address) v4.add(a);
            return v4.isEmpty() ? all : v4;
        }).build();
        return ipv4;
    }

    /**
     * v20 (3.1): a googlevideo link is signed for ONE address (its "ip=" parameter). His 3.0 run read the links over
     * IPv4 (yt-dlp -4) and downloaded them with the dual-stack client: connections that went out over IPv6 were refused
     * (HTTP 403 mid-download). Downloads now use the same address family the link was made for.
     */
    public static String linkFamily(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q < 0) return "";
        for (String kv : url.substring(q + 1).split("&")) {
            if (!kv.startsWith("ip=")) continue;
            String ip;
            try { ip = java.net.URLDecoder.decode(kv.substring(3), "UTF-8"); } catch (Exception e) { ip = kv.substring(3); }
            if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return "4";
            if (ip.contains(":")) return "6";
            return "";
        }
        return "";
    }

    private static okhttp3.Dns family(final boolean v4) {
        return host -> {
            java.util.List<java.net.InetAddress> all = okhttp3.Dns.SYSTEM.lookup(host), keep = new java.util.ArrayList<>();
            for (java.net.InetAddress a : all) if ((a instanceof java.net.Inet4Address) == v4) keep.add(a);
            return keep.isEmpty() ? all : keep;
        };
    }

    private static OkHttpClient download4, download6;
    /** v20: the parallel-download client for this link's address family (see {@link #linkFamily}) */
    public static synchronized OkHttpClient downloadFor(String url) {
        String fam = linkFamily(url);
        if ("4".equals(fam)) {
            if (download4 == null) download4 = download().newBuilder().connectionPool(new ConnectionPool(16, 2, TimeUnit.MINUTES)).dns(family(true)).build();
            return download4;
        }
        if ("6".equals(fam)) {
            if (download6 == null) download6 = download().newBuilder().connectionPool(new ConnectionPool(16, 2, TimeUnit.MINUTES)).dns(family(false)).build();
            return download6;
        }
        return download();
    }

    private static OkHttpClient fast6;
    /** v20: a short-timeout client for this link's address family (link checks) */
    public static synchronized OkHttpClient fastFor(String url) {
        String fam = linkFamily(url);
        if ("4".equals(fam)) return ipv4();
        if ("6".equals(fam)) {
            if (fast6 == null) fast6 = fast().newBuilder().connectionPool(new ConnectionPool(2, 1, TimeUnit.MINUTES)).dns(family(false)).build();
            return fast6;
        }
        return fast();
    }

    public static void main(Runnable r) { MAIN.post(r); }
}
