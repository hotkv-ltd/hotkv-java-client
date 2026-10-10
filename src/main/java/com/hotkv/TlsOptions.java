// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/**
 * TLS configuration for a HotKV connection. Instances are immutable; build
 * one with {@link #builder()}.
 *
 * <p>Private keys must be PEM encoded PKCS8 ({@code -----BEGIN PRIVATE
 * KEY-----}). A traditional PKCS1 {@code -----BEGIN RSA PRIVATE KEY-----}
 * file can be converted with:
 * {@code openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem}.
 */
public final class TlsOptions {

    private final boolean enabled;
    private final String caCertPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final String serverName;
    private final boolean insecureSkipVerify;

    private TlsOptions(Builder b) {
        this.enabled = b.enabled;
        this.caCertPath = b.caCertPath;
        this.clientCertPath = b.clientCertPath;
        this.clientKeyPath = b.clientKeyPath;
        this.serverName = b.serverName;
        this.insecureSkipVerify = b.insecureSkipVerify;
    }

    public boolean enabled() {
        return enabled;
    }

    public String caCertPath() {
        return caCertPath;
    }

    public String clientCertPath() {
        return clientCertPath;
    }

    public String clientKeyPath() {
        return clientKeyPath;
    }

    public String serverName() {
        return serverName;
    }

    /** When true, the server's certificate chain and hostname are not verified. Tests only. */
    public boolean insecureSkipVerify() {
        return insecureSkipVerify;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TlsOptions disabled() {
        return new Builder().enabled(false).build();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .caCertPath(caCertPath)
                .clientCert(clientCertPath, clientKeyPath)
                .serverName(serverName)
                .insecureSkipVerify(insecureSkipVerify);
    }

    public static final class Builder {
        private boolean enabled = true;
        private String caCertPath;
        private String clientCertPath;
        private String clientKeyPath;
        private String serverName;
        private boolean insecureSkipVerify;

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** PEM file with one or more trusted CA certificates. Omit to trust the JVM's default CA set. */
        public Builder caCertPath(String path) {
            this.caCertPath = path;
            return this;
        }

        /** PEM encoded client certificate (chain) and PKCS8 private key, for mutual TLS. */
        public Builder clientCert(String certPath, String keyPath) {
            this.clientCertPath = certPath;
            this.clientKeyPath = keyPath;
            return this;
        }

        /** Overrides the hostname used for certificate verification and SNI. */
        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        /** Disables certificate and hostname verification. Never use this against a production server. */
        public Builder insecureSkipVerify(boolean insecureSkipVerify) {
            this.insecureSkipVerify = insecureSkipVerify;
            return this;
        }

        public TlsOptions build() {
            return new TlsOptions(this);
        }
    }
}
