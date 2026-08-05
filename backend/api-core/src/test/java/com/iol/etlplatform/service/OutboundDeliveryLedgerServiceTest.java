package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.OutboundDeliveryLedgerRequest;
import com.iol.etlplatform.dto.interop.OutboundDeliveryLedgerResponse;
import com.iol.etlplatform.entity.OutboundDeliveryRecord;
import com.iol.etlplatform.repository.OutboundDeliveryRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboundDeliveryLedgerServiceTest {

    @Test
    void claimReturnsSharedMongoLease() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        OutboundDeliveryRecordRepository repository = mock(OutboundDeliveryRecordRepository.class);
        OutboundDeliveryRecord claimed = OutboundDeliveryRecord.builder()
                .status("IN_PROGRESS")
                .leaseOwner("worker-a")
                .attempts(1)
                .build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(OutboundDeliveryRecord.class))).thenReturn(claimed);

        OutboundDeliveryLedgerResponse response = new OutboundDeliveryLedgerService(mongoTemplate, repository)
                .claim(request("corr-1", "worker-a"));

        assertEquals("CLAIMED", response.result());
        assertEquals(1, response.attempts());
    }

    @Test
    void duplicateDeliveredClaimIsNotDeliveredAgain() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        OutboundDeliveryRecordRepository repository = mock(OutboundDeliveryRecordRepository.class);
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(OutboundDeliveryRecord.class))).thenThrow(new DuplicateKeyException("already exists"));
        when(repository.findById(anyString())).thenReturn(Optional.of(
                OutboundDeliveryRecord.builder().status("DELIVERED").attempts(1).build()));

        OutboundDeliveryLedgerResponse response = new OutboundDeliveryLedgerService(mongoTemplate, repository)
                .claim(request("corr-1", "worker-b"));

        assertEquals("ALREADY_DELIVERED", response.result());
        assertEquals(1, response.attempts());
    }

    private OutboundDeliveryLedgerRequest request(String key, String owner) {
        OutboundDeliveryLedgerRequest request = new OutboundDeliveryLedgerRequest();
        request.setIdempotencyKey(key);
        request.setOwner(owner);
        request.setLeaseSeconds(300L);
        return request;
    }
}
