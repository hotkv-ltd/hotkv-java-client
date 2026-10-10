// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link HotKVOptions#parse}; no server needed. */
class HotKVOptionsTest {

    @Test
    void plainHotkvUrl() {
        HotKVOptions o = HotKVOptions.parse("hotkv://127.0.0.1:6399").build();
        assertEquals("127.0.0.1", o.host());
        assertEquals(6399, o.port());
        assertFalse(o.tls().enabled());
        assertNull(o.password());
        assertEquals(0, o.database());
    }

    @Test
    void defaultPortWhenOmitted() {
        HotKVOptions o = HotKVOptions.parse("hotkv://myhost").build();
        assertEquals("myhost", o.host());
        assertEquals(HotKVOptions.DEFAULT_PORT, o.port());
    }

    @Test
    void tlsSchemeEnablesTls() {
        HotKVOptions o = HotKVOptions.parse("hotkvs://cache.example.com:6400").build();
        assertTrue(o.tls().enabled());
        assertEquals("cache.example.com", o.tls().serverName());
    }

    @Test
    void redisSchemesAreAcceptedForDropInMigration() {
        assertFalse(HotKVOptions.parse("redis://localhost:6379").build().tls().enabled());
        assertTrue(HotKVOptions.parse("rediss://localhost:6379").build().tls().enabled());
    }

    @Test
    void userAndPassword() {
        HotKVOptions o = HotKVOptions.parse("hotkv://alice:s3cret@host:6399").build();
        assertEquals("alice", o.username());
        assertEquals("s3cret", o.password());
    }

    @Test
    void legacyPasswordOnlyForm() {
        HotKVOptions o1 = HotKVOptions.parse("hotkv://:s3cret@host:6399").build();
        assertNull(o1.username());
        assertEquals("s3cret", o1.password());

        HotKVOptions o2 = HotKVOptions.parse("hotkv://s3cret@host:6399").build();
        assertNull(o2.username());
        assertEquals("s3cret", o2.password());
    }

    @Test
    void databaseIndexFromPath() {
        HotKVOptions o = HotKVOptions.parse("hotkv://host:6399/3").build();
        assertEquals(3, o.database());
    }

    @Test
    void invalidDatabaseIndexRejected() {
        assertThrows(IllegalArgumentException.class, () -> HotKVOptions.parse("hotkv://host:6399/notanumber").build());
    }

    @Test
    void unsupportedSchemeRejected() {
        assertThrows(IllegalArgumentException.class, () -> HotKVOptions.parse("ftp://host:21").build());
    }

    @Test
    void unixSchemeSetsSocketPath() {
        HotKVOptions o = HotKVOptions.parse("unix:///tmp/hotkv.sock").build();
        assertEquals("/tmp/hotkv.sock", o.unixSocketPath());
    }

    @Test
    void builderOverridesAfterParsing() {
        HotKVOptions o = HotKVOptions.parse("hotkv://host:6399").poolSize(25).commandTimeoutMillis(1234).build();
        assertEquals(25, o.poolSize());
        assertEquals(1234, o.commandTimeoutMillis());
    }

    @Test
    void defaultsAreSensible() {
        HotKVOptions o = HotKVOptions.builder().build();
        assertTrue(o.resp3());
        assertTrue(o.poolSize() > 0);
        assertTrue(o.maxRetries() >= 0);
        assertFalse(o.retryNonIdempotentWrites());
    }
}
