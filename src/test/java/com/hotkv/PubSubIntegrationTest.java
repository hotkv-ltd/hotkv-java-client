// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Integration tests for Pub/Sub (SUBSCRIBE/PSUBSCRIBE, listener and pull delivery). */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
@Timeout(30)
class PubSubIntegrationTest {

    private static HotKV client;

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @Test
    void pullStyleDeliversPublishedMessage() throws InterruptedException {
        String channel = uniqueKey("chan-pull");
        try (PubSub sub = client.pubSub()) {
            sub.subscribe(channel);

            Thread publisher = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                client.publish(channel, "hello");
            });
            publisher.start();

            PubSubMessage msg = sub.nextMessage();
            assertEquals(PubSubMessage.Kind.MESSAGE, msg.kind());
            assertEquals(channel, msg.channel());
            assertEquals("hello", msg.payload());
            publisher.join();
        }
    }

    @Test
    void listenerStyleDeliversPatternMessage() throws InterruptedException {
        String pattern = uniqueKey("chan-pattern") + ".*";
        String channel = pattern.substring(0, pattern.length() - 1) + "one";
        BlockingQueue<PubSubMessage> received = new LinkedBlockingQueue<>();

        try (PubSub sub = client.pubSub()) {
            sub.addListener(received::add);
            sub.psubscribe(pattern);
            Thread.sleep(150); // let the background delivery thread start reading before we publish

            long subscribers = client.publish(channel, "world");
            assertEquals(1, subscribers);

            PubSubMessage msg = received.poll(10, TimeUnit.SECONDS);
            assertNotNull(msg, "expected a pmessage to be delivered to the listener");
            assertEquals(PubSubMessage.Kind.PMESSAGE, msg.kind());
            assertEquals(pattern, msg.pattern());
            assertEquals(channel, msg.channel());
            assertEquals("world", msg.payload());
        }
    }

    @Test
    void ssubscribeDeliversShardMessage() throws InterruptedException {
        String channel = uniqueKey("schan");
        try (PubSub sub = client.pubSub()) {
            sub.ssubscribe(channel);

            Thread publisher = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                client.sPublish(channel, "shard-hello");
            });
            publisher.start();

            PubSubMessage msg = sub.nextMessage();
            assertEquals(PubSubMessage.Kind.SMESSAGE, msg.kind());
            assertEquals(channel, msg.channel());
            assertEquals("shard-hello", msg.payload());
            publisher.join();

            sub.sunsubscribe(channel); // should not throw; consumes the sunsubscribe acknowledgement
        }
    }

    @Test
    void unsubscribeAcknowledged() {
        String channel = uniqueKey("chan-unsub");
        try (PubSub sub = client.pubSub()) {
            sub.subscribe(channel);
            sub.unsubscribe(channel); // should not throw; consumes the unsubscribe acknowledgement
        }
    }
}
