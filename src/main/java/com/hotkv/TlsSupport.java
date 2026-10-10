// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Builds and configures the {@link SSLSocket} layered over a raw TCP connection. */
final class TlsSupport {

    private TlsSupport() {
    }

    static SSLSocket handshake(Socket raw, String host, int port, TlsOptions tls) throws IOException {
        try {
            SSLSocketFactory factory = buildSocketFactory(tls);
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            String sni = tls.serverName() != null ? tls.serverName() : host;
            if (!isNumericAddress(sni)) {
                params.setServerNames(List.of(new javax.net.ssl.SNIHostName(sni)));
            }
            if (!tls.insecureSkipVerify()) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            return ssl;
        } catch (GeneralSecurityException e) {
            throw new IOException("hotkv: failed to configure TLS: " + e.getMessage(), e);
        }
    }

    private static boolean isNumericAddress(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    private static SSLSocketFactory buildSocketFactory(TlsOptions tls) throws GeneralSecurityException, IOException {
        TrustManager[] trustManagers;
        if (tls.insecureSkipVerify()) {
            trustManagers = new TrustManager[] {insecureTrustManager()};
        } else if (tls.caCertPath() != null) {
            trustManagers = trustManagersFromCaFile(tls.caCertPath());
        } else {
            trustManagers = null;
        }

        KeyManager[] keyManagers = null;
        if (tls.clientCertPath() != null && tls.clientKeyPath() != null) {
            keyManagers = keyManagersFromFiles(tls.clientCertPath(), tls.clientKeyPath());
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keyManagers, trustManagers, new SecureRandom());
        return ctx.getSocketFactory();
    }

    private static TrustManager insecureTrustManager() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
            }
        };
    }

    private static TrustManager[] trustManagersFromCaFile(String path) throws GeneralSecurityException, IOException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            certs = new ArrayList<>(cf.generateCertificates(in));
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate c : certs) {
            ks.setCertificateEntry("hotkv-ca-" + (i++), c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    private static KeyManager[] keyManagersFromFiles(String certPath, String keyPath)
            throws GeneralSecurityException, IOException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain;
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            chain = new ArrayList<>(cf.generateCertificates(in));
        }
        PrivateKey key = readPrivateKey(Path.of(keyPath));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("hotkv-client", key, new char[0], chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        return kmf.getKeyManagers();
    }

    private static PrivateKey readPrivateKey(Path path) throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")) {
            throw new InvalidKeyException(
                    "hotkv: PKCS1/SEC1 private key PEM files are not supported, convert to PKCS8 first: "
                            + "openssl pkcs8 -topk8 -nocrypt -in " + path + " -out " + path + ".pk8.pem");
        }
        String base64 = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        GeneralSecurityException last = null;
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519", "DSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw new InvalidKeyException("hotkv: unsupported private key format in " + path, last);
    }
}
