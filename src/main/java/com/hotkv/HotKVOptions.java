// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Configuration for a {@link HotKV} client. Instances are immutable; build
 * one with {@link #builder()} or parse a connection URL with
 * {@link #parse(String)}.
 *
 * <p>Recognised URL schemes are {@code hotkv://} (plain), {@code
 * hotkvs://} (TLS), and, for drop in migration, {@code redis://}/{@code
 * rediss://} which behave identically. A leading path segment selects the
 * database, e.g. {@code hotkv://user:pass@host:6399/2}.
 */
public final class HotKVOptions {

    public static final int DEFAULT_PORT = 6379;

    private final String host;
    private final int port;
    private final String unixSocketPath;
    private final String username;
    private final String password;
    private final int database;
    private final String clientName;
    private final int connectTimeoutMillis;
    private final int commandTimeoutMillis;
    private final TlsOptions tls;
    private final boolean resp3;
    private final int poolSize;
    private final int minIdleConnections;
    private final long idleTimeoutMillis;
    private final long healthCheckIntervalMillis;
    private final long poolBorrowTimeoutMillis;
    private final int maxRetries;
    private final boolean retryNonIdempotentWrites;

    private HotKVOptions(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.unixSocketPath = b.unixSocketPath;
        this.username = b.username;
        this.password = b.password;
        this.database = b.database;
        this.clientName = b.clientName;
        this.connectTimeoutMillis = b.connectTimeoutMillis;
        this.commandTimeoutMillis = b.commandTimeoutMillis;
        this.tls = b.tls;
        this.resp3 = b.resp3;
        this.poolSize = b.poolSize;
        this.minIdleConnections = b.minIdleConnections;
        this.idleTimeoutMillis = b.idleTimeoutMillis;
        this.healthCheckIntervalMillis = b.healthCheckIntervalMillis;
        this.poolBorrowTimeoutMillis = b.poolBorrowTimeoutMillis;
        this.maxRetries = b.maxRetries;
        this.retryNonIdempotentWrites = b.retryNonIdempotentWrites;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Filesystem path of a Unix domain socket to connect to instead of {@link #host()}/{@link #port()}, or null. */
    public String unixSocketPath() {
        return unixSocketPath;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public int database() {
        return database;
    }

    public String clientName() {
        return clientName;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int commandTimeoutMillis() {
        return commandTimeoutMillis;
    }

    public TlsOptions tls() {
        return tls;
    }

    /** Whether the client negotiates RESP3 with {@code HELLO 3}, falling back to RESP2 automatically. */
    public boolean resp3() {
        return resp3;
    }

    public int poolSize() {
        return poolSize;
    }

    public int minIdleConnections() {
        return minIdleConnections;
    }

    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public long healthCheckIntervalMillis() {
        return healthCheckIntervalMillis;
    }

    public long poolBorrowTimeoutMillis() {
        return poolBorrowTimeoutMillis;
    }

    /** Number of times an idempotent read is retried on a fresh connection after a connection loss. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Whether {@link HotKV} may also retry non idempotent writes on connection loss. Default false. */
    public boolean retryNonIdempotentWrites() {
        return retryNonIdempotentWrites;
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.host = host;
        b.port = port;
        b.unixSocketPath = unixSocketPath;
        b.username = username;
        b.password = password;
        b.database = database;
        b.clientName = clientName;
        b.connectTimeoutMillis = connectTimeoutMillis;
        b.commandTimeoutMillis = commandTimeoutMillis;
        b.tls = tls;
        b.resp3 = resp3;
        b.poolSize = poolSize;
        b.minIdleConnections = minIdleConnections;
        b.idleTimeoutMillis = idleTimeoutMillis;
        b.healthCheckIntervalMillis = healthCheckIntervalMillis;
        b.poolBorrowTimeoutMillis = poolBorrowTimeoutMillis;
        b.maxRetries = maxRetries;
        b.retryNonIdempotentWrites = retryNonIdempotentWrites;
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Parses a {@code hotkv://}/{@code hotkvs://}/{@code redis://}/{@code rediss://} connection URL. */
    public static Builder parse(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("hotkv: connection URL must not be empty");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("hotkv: invalid connection URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        Builder b = new Builder();
        switch (scheme) {
            case "hotkv":
            case "redis":
                b.tls = TlsOptions.disabled();
                break;
            case "hotkvs":
            case "rediss":
                b.tls = TlsOptions.builder().build();
                break;
            case "unix":
                b.unixSocketPath = uri.getPath();
                return b;
            default:
                throw new IllegalArgumentException(
                        "hotkv: unsupported URL scheme '" + scheme + "' (want hotkv, hotkvs, redis, rediss or unix)");
        }

        String host = uri.getHost();
        b.host = (host == null || host.isEmpty()) ? DEFAULT_HOST : host;
        b.port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                b.username = decode(userInfo.substring(0, colon));
                b.password = decode(userInfo.substring(colon + 1));
                if (b.username.isEmpty()) {
                    b.username = null;
                }
            } else {
                // A single userinfo component with no colon is the legacy single-password
                // AUTH form, e.g. redis://:secret@host is canonical but redis://secret@host
                // is accepted too.
                b.password = decode(userInfo);
            }
        }

        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            String dbPart = path.substring(1);
            try {
                b.database = Integer.parseInt(dbPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("hotkv: invalid database index in URL path: '" + dbPart + "'", e);
            }
        }

        if (b.tls != null && b.tls.enabled()) {
            b.tls = b.tls.toBuilder().serverName(b.host).build();
        }

        return b;
    }

    private static final String DEFAULT_HOST = "127.0.0.1";

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String unixSocketPath;
        private String username;
        private String password;
        private int database;
        private String clientName;
        private int connectTimeoutMillis = 5_000;
        private int commandTimeoutMillis = 5_000;
        private TlsOptions tls = TlsOptions.disabled();
        private boolean resp3 = true;
        private int poolSize = 10;
        private int minIdleConnections = 0;
        private long idleTimeoutMillis = 5 * 60_000L;
        private long healthCheckIntervalMillis = 5_000L;
        private long poolBorrowTimeoutMillis = 5_000L;
        private int maxRetries = 1;
        private boolean retryNonIdempotentWrites = false;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder unixSocketPath(String path) {
            this.unixSocketPath = path;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder clientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        public Builder connectTimeoutMillis(int millis) {
            this.connectTimeoutMillis = millis;
            return this;
        }

        public Builder commandTimeoutMillis(int millis) {
            this.commandTimeoutMillis = millis;
            return this;
        }

        public Builder tls(TlsOptions tls) {
            this.tls = tls;
            return this;
        }

        /** Enables TLS with default {@link TlsOptions}. */
        public Builder tls() {
            this.tls = TlsOptions.builder().build();
            return this;
        }

        public Builder resp3(boolean resp3) {
            this.resp3 = resp3;
            return this;
        }

        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder minIdleConnections(int minIdleConnections) {
            this.minIdleConnections = minIdleConnections;
            return this;
        }

        public Builder idleTimeoutMillis(long millis) {
            this.idleTimeoutMillis = millis;
            return this;
        }

        public Builder healthCheckIntervalMillis(long millis) {
            this.healthCheckIntervalMillis = millis;
            return this;
        }

        public Builder poolBorrowTimeoutMillis(long millis) {
            this.poolBorrowTimeoutMillis = millis;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryNonIdempotentWrites(boolean retry) {
            this.retryNonIdempotentWrites = retry;
            return this;
        }

        public HotKVOptions build() {
            if (tls == null) {
                tls = TlsOptions.disabled();
            }
            return new HotKVOptions(this);
        }
    }
}
