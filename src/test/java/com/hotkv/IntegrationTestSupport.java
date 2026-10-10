// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.NotLicensedException;

import java.util.UUID;

/** Shared helpers for integration tests, which all run only when {@code HOTKV_TEST_URL} is set. */
public final class IntegrationTestSupport {

    private IntegrationTestSupport() {
    }

    public static String testUrl() {
        return System.getenv("HOTKV_TEST_URL");
    }

    public static HotKV client() {
        return HotKV.create(testUrl());
    }

    /** A key prefix unique to this test run, so concurrent test classes never collide. */
    public static String uniqueKey(String label) {
        return "hkjtest:" + label + ":" + UUID.randomUUID();
    }

    /**
     * Runs a check against an enterprise/AI command family. A server whose license does not
     * cover the family replies NOLICENSE; this skips with a clear reason on
     * {@link NotLicensedException} so the test runs for real once a suitable license is present.
     */
    public static void runOrSkipIfNotLicensed(String feature, Runnable body) {
        try {
            body.run();
        } catch (NotLicensedException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "license feature '" + feature + "' is not enabled on the server: " + e.getMessage());
        }
    }

    /**
     * Skips the calling test or class when the server is not licensed for enterprise commands.
     * It runs a real enterprise command and treats {@link NotLicensedException} as the signal.
     */
    public static void assumeLicensed(HotKV client) {
        try {
            client.observe().info();
        } catch (NotLicensedException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "the server is not licensed for enterprise commands");
        }
    }
}
