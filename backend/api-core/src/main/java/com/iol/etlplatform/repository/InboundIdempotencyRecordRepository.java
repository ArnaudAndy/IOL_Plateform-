package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.InboundIdempotencyRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InboundIdempotencyRecordRepository
        extends MongoRepository<InboundIdempotencyRecord, String> {
}
