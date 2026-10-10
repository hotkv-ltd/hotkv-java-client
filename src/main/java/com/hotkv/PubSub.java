// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Pub/Sub session on its own dedicated connection (never shared with the
 * pool), matching RESP3's requirement that push frames are read on the
 * connection that subscribed. Use either style, not both, per instance:
 *
 * <ul>
 *   <li>Callback: {@link #addListener} then {@link #subscribe}/{@link #psubscribe}; a
 *       background thread delivers events until {@link #close()}.</li>
 *   <li>Pull: {@link #subscribe}/{@link #psubscribe} then repeatedly call
 *       {@link #nextMessage()} yourself.</li>
 * </ul>
 */
public final class PubSub implements Closeable {

    private final HotKVEngine engine;
    private final List<PubSubListener> listeners = new CopyOnWriteArrayList<>();
    private Connection conn;
    private Thread listenerThread;
    private volatile boolean closed;

    PubSub(HotKVEngine engine) {
        this.engine = engine;
    }

    public synchronized void subscribe(String... channels) {
        sendSubscribeCommand("SUBSCRIBE", channels);
    }

    public synchronized void psubscribe(String... patterns) {
        sendSubscribeCommand("PSUBSCRIBE", patterns);
    }

    public synchronized void ssubscribe(String... channels) {
        sendSubscribeCommand("SSUBSCRIBE", channels);
    }

    public synchronized void unsubscribe(String... channels) {
        sendSubscribeCommand("UNSUBSCRIBE", channels);
    }

    public synchronized void punsubscribe(String... patterns) {
        sendSubscribeCommand("PUNSUBSCRIBE", patterns);
    }

    public synchronized void sunsubscribe(String... channels) {
        sendSubscribeCommand("SUNSUBSCRIBE", channels);
    }

    private void sendSubscribeCommand(String cmd, String... items) {
        if (items.length == 0) {
            return;
        }
        try {
            Connection c = connection();
            c.send(HotKV.prepend(cmd, items));
            c.flush();
            for (int i = 0; i < items.length; i++) {
                c.receive(); // per item subscribe/unsubscribe acknowledgement
            }
        } catch (IOException e) {
            markBrokenAndClose();
            throw HotKVEngine.translate(e);
        }
    }

    private synchronized Connection connection() {
        if (conn == null) {
            conn = engine.detachForPubSub();
        }
        return conn;
    }

    /** Registers a callback for every event; starts the background delivery thread if not already running. */
    public void addListener(PubSubListener listener) {
        listeners.add(listener);
        ensureListenerThread();
    }

    public void removeListener(PubSubListener listener) {
        listeners.remove(listener);
    }

    private synchronized void ensureListenerThread() {
        if (listenerThread == null && !closed) {
            listenerThread = new Thread(this::listenLoop, "hotkv-pubsub");
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
    }

    private void listenLoop() {
        while (!closed) {
            PubSubMessage msg;
            try {
                msg = readNextMessage();
            } catch (IOException e) {
                return; // connection closed or broken; nothing more to deliver
            }
            if (msg == null) {
                continue;
            }
            for (PubSubListener l : listeners) {
                try {
                    l.onMessage(msg);
                } catch (RuntimeException ignored) {
                    // A listener's own bug must not take down the delivery thread.
                }
            }
        }
    }

    /** Blocks for the next event on this session. Do not mix with {@link #addListener}. */
    public PubSubMessage nextMessage() {
        try {
            return readNextMessage();
        } catch (IOException e) {
            markBrokenAndClose();
            throw HotKVEngine.translate(e);
        }
    }

    private PubSubMessage readNextMessage() throws IOException {
        RespValue v = connection().receive();
        List<RespValue> items = Reply.arrayOrEmpty(v);
        if (items.size() < 3) {
            return null;
        }
        String type = Reply.string(items.get(0));
        return switch (type) {
            case "message" -> new PubSubMessage(PubSubMessage.Kind.MESSAGE,
                    Reply.string(items.get(1)), null, Reply.string(items.get(2)), null);
            case "pmessage" -> new PubSubMessage(PubSubMessage.Kind.PMESSAGE,
                    Reply.string(items.get(2)), Reply.string(items.get(1)), Reply.string(items.get(3)), null);
            case "smessage" -> new PubSubMessage(PubSubMessage.Kind.SMESSAGE,
                    Reply.string(items.get(1)), null, Reply.string(items.get(2)), null);
            case "subscribe" -> new PubSubMessage(PubSubMessage.Kind.SUBSCRIBE,
                    Reply.string(items.get(1)), null, null, Reply.integer(items.get(2)));
            case "unsubscribe" -> new PubSubMessage(PubSubMessage.Kind.UNSUBSCRIBE,
                    Reply.string(items.get(1)), null, null, Reply.integer(items.get(2)));
            case "psubscribe" -> new PubSubMessage(PubSubMessage.Kind.PSUBSCRIBE,
                    null, Reply.string(items.get(1)), null, Reply.integer(items.get(2)));
            case "punsubscribe" -> new PubSubMessage(PubSubMessage.Kind.PUNSUBSCRIBE,
                    null, Reply.string(items.get(1)), null, Reply.integer(items.get(2)));
            case "ssubscribe" -> new PubSubMessage(PubSubMessage.Kind.SSUBSCRIBE,
                    Reply.string(items.get(1)), null, null, Reply.integer(items.get(2)));
            case "sunsubscribe" -> new PubSubMessage(PubSubMessage.Kind.SUNSUBSCRIBE,
                    Reply.string(items.get(1)), null, null, Reply.integer(items.get(2)));
            default -> null;
        };
    }

    private void markBrokenAndClose() {
        if (conn != null) {
            conn.markBroken();
        }
        close();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (conn != null) {
            conn.close();
        }
    }
}
