// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespReader;
import com.hotkv.protocol.RespValue;
import com.hotkv.protocol.RespWriter;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * One physical connection to a HotKV server: a socket (TCP, TLS or Unix
 * domain), the RESP reader/writer pair over it, and the HELLO/AUTH/SELECT
 * handshake performed when it was opened. Not thread safe: the pool hands
 * out at most one caller at a time.
 */
final class Connection implements Closeable {

    private final Closeable transport;
    private final Socket socket;
    private final RespReader reader;
    private final RespWriter writer;
    private boolean resp3;
    private volatile long lastUsedNanos = System.nanoTime();
    private volatile boolean broken;

    private Connection(Closeable transport, Socket socket, InputStream in, OutputStream out) {
        this.transport = transport;
        this.socket = socket;
        this.reader = new RespReader(in);
        this.writer = new RespWriter(new BufferedOutputStream(out, 32 * 1024));
    }

    static Connection connect(HotKVOptions options) throws IOException {
        Connection conn = options.unixSocketPath() != null ? openUnix(options) : openTcp(options);
        try {
            conn.applySocketTimeout(options.commandTimeoutMillis());
            conn.handshake(options);
            return conn;
        } catch (IOException | RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    private static Connection openTcp(HotKVOptions options) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(options.host(), options.port()), options.connectTimeoutMillis());
        raw.setTcpNoDelay(true);
        Socket active = raw;
        if (options.tls().enabled()) {
            active = TlsSupport.handshake(raw, options.host(), options.port(), options.tls());
        }
        return new Connection(active, active, active.getInputStream(), active.getOutputStream());
    }

    private static Connection openUnix(HotKVOptions options) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(options.unixSocketPath()));
        return new Connection(channel, null, Channels.newInputStream(channel), Channels.newOutputStream(channel));
    }

    private void applySocketTimeout(int millis) throws IOException {
        if (socket != null) {
            socket.setSoTimeout(millis);
        }
    }

    private void handshake(HotKVOptions options) throws IOException {
        boolean helloOk = sendHello(options, options.resp3() ? "3" : "2");
        if (!helloOk && options.resp3()) {
            helloOk = sendHello(options, "2");
        }
        if (!helloOk) {
            legacyHandshake(options);
        } else if (options.database() != 0) {
            selectDatabase(options.database());
        }
    }

    private boolean sendHello(HotKVOptions options, String protover) throws IOException {
        List<Object> args = new ArrayList<>(6);
        args.add("HELLO");
        args.add(protover);
        if (options.password() != null) {
            args.add("AUTH");
            args.add(options.username() != null ? options.username() : "default");
            args.add(options.password());
        }
        if (options.clientName() != null) {
            args.add("SETNAME");
            args.add(options.clientName());
        }
        RespValue reply = call(args.toArray());
        if (reply.isError()) {
            return false;
        }
        this.resp3 = "3".equals(protover);
        return true;
    }

    private void legacyHandshake(HotKVOptions options) throws IOException {
        this.resp3 = false;
        if (options.password() != null) {
            RespValue reply = options.username() != null
                    ? call("AUTH", options.username(), options.password())
                    : call("AUTH", options.password());
            if (reply.isError()) {
                throw new IOException("hotkv: authentication failed: " + reply.errorMessage());
            }
        }
        if (options.clientName() != null) {
            RespValue reply = call("CLIENT", "SETNAME", options.clientName());
            if (reply.isError()) {
                throw new IOException("hotkv: CLIENT SETNAME failed: " + reply.errorMessage());
            }
        }
        if (options.database() != 0) {
            selectDatabase(options.database());
        }
    }

    private void selectDatabase(int db) throws IOException {
        RespValue reply = call("SELECT", Integer.toString(db));
        if (reply.isError()) {
            throw new IOException("hotkv: SELECT " + db + " failed: " + reply.errorMessage());
        }
    }

    /** Sends one command and waits for its reply: a single round trip. */
    RespValue call(Object... args) throws IOException {
        writer.writeCommand(args);
        writer.flush();
        RespValue v = reader.readValue();
        touch();
        return v;
    }

    /** Queues a command without flushing; pair with {@link #flush()} and {@link #receive()} for pipelining. */
    void send(Object... args) throws IOException {
        writer.writeCommand(args);
    }

    void send(List<?> args) throws IOException {
        writer.writeCommand(args);
    }

    void flush() throws IOException {
        writer.flush();
        touch();
    }

    RespValue receive() throws IOException {
        RespValue v = reader.readValue();
        touch();
        return v;
    }

    boolean resp3() {
        return resp3;
    }

    void touch() {
        lastUsedNanos = System.nanoTime();
    }

    long idleMillis() {
        return (System.nanoTime() - lastUsedNanos) / 1_000_000L;
    }

    void markBroken() {
        broken = true;
    }

    boolean isBroken() {
        return broken;
    }

    void setSoTimeoutQuiet(int millis) {
        if (socket != null) {
            try {
                socket.setSoTimeout(millis);
            } catch (IOException ignored) {
                markBroken();
            }
        }
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException ignored) {
            // Closing a broken connection is best effort.
        }
    }
}
