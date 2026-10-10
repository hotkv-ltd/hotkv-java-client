// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Best effort smoke test for {@link HotKVCluster} against a single node
 * server, which is not started in cluster mode. The routing and
 * redirect handling logic itself (slot map parsing, MOVED/ASK, CRC16) is
 * covered without a server by {@link SlotMapTest}, {@link Crc16Test} and
 * {@link ErrorsTest}; here we only confirm that a single node deployment
 * either exposes an (empty) slot map cleanly or is reported as a clear,
 * typed connection failure, never an unhandled exception.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class ClusterSmokeIntegrationTest {

    @Test
    void singleNodeDeploymentFailsCleanlyOrExposesAnEmptyTopology() {
        HotKVOptions template = HotKVOptions.parse(IntegrationTestSupport.testUrl()).build();
        String address = template.host() + ":" + template.port();

        HotKVCluster cluster;
        try {
            cluster = new HotKVCluster(template, java.util.List.of(address));
        } catch (HotKVException notClustered) {
            Assumptions.assumeTrue(false, "server is not running in cluster mode: " + notClustered.getMessage());
            return;
        }
        try {
            // A single, non-clustered node reports no slot ranges; routing must say so
            // clearly rather than hang or throw something unexpected.
            assertEquals(0, cluster.topology().size());
        } finally {
            cluster.close();
        }
    }
}
