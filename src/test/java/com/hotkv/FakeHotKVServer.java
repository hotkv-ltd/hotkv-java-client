// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespReader;
import com.hotkv.protocol.RespValue;
import com.hotkv.protocol.RespValue.RespArray;
import com.hotkv.protocol.RespValue.RespBulkString;
import com.hotkv.protocol.RespValue.RespSimpleString;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A tiny loopback-only RESP fake used to unit test argument building and
 * reply decoding without a HotKV server: it answers the client's HELLO
 * handshake with an unconditional {@code +OK} (content is never inspected
 * by {@code Connection.sendHello}, only whether it is an error), then hands
 * the next command's decoded argument list to whatever body {@link
 * #exchange} runs, replying with the given raw RESP bytes.
 *
 * <p>The command under test runs on a background thread while this
 * object's own logic (accepting the connection, answering HELLO, capturing
 * the real command) runs on the calling thread, so {@link #lastArgs} is
 * set before {@link #exchange} returns without needing any extra
 * synchronization.
 */
final class FakeHotKVServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
    private volatile List<String> lastArgs;

    private FakeHotKVServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    static FakeHotKVServer start() throws IOException {
        return new FakeHotKVServer(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
    }

    List<String> lastArgs() {
        return lastArgs;
    }

    HotKV newClient() {
        return new HotKV(HotKVOptions.builder()
                .host("127.0.0.1")
                .port(serverSocket.getLocalPort())
                .connectTimeoutMillis(2000)
                .commandTimeoutMillis(2000)
                .build());
    }

    /**
     * Runs {@code body} against a fresh client on a background thread, answers its HELLO
     * handshake, captures its one real command's argument list into {@link #lastArgs}, replies
     * with {@code raw}, and returns body's result (propagating any exception it threw).
     */
    <T> T exchange(String raw, Function<HotKV, T> body) throws Exception {
        HotKV client = newClient();
        Future<T> future = clientExecutor.submit(() -> body.apply(client));
        try (Socket socket = serverSocket.accept()) {
            RespReader reader = new RespReader(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            reader.readValue(); // HELLO n [...]
            out.write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            RespValue cmd = reader.readValue();
            lastArgs = toArgs(cmd);
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        try {
            return future.get(5, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    private static List<String> toArgs(RespValue v) {
        List<String> out = new ArrayList<>();
        if (v instanceof RespArray a) {
            for (RespValue item : a.items()) {
                if (item instanceof RespBulkString b) {
                    out.add(b.asUtf8());
                } else if (item instanceof RespSimpleString s) {
                    out.add(s.value());
                }
            }
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        clientExecutor.shutdownNow();
        serverSocket.close();
    }
}
