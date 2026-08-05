package com.iol.etlplatform.pipelineconsumer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DistributedExecutionLockServiceTest {

    @Test
    void advisoryLockIdIsStableAndScopedByExecutionKey() {
        DistributedExecutionLockService service = new DistributedExecutionLockService();

        assertEquals(
                service.advisoryLockId("destination:mysql-hospital-b"),
                service.advisoryLockId("destination:mysql-hospital-b"));
        assertNotEquals(
                service.advisoryLockId("destination:mysql-hospital-b"),
                service.advisoryLockId("destination:postgres-hospital-c"));
    }
}
