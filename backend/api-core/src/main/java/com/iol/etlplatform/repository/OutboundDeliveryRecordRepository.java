package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.OutboundDeliveryRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboundDeliveryRecordRepository extends MongoRepository<OutboundDeliveryRecord, String> {
}
