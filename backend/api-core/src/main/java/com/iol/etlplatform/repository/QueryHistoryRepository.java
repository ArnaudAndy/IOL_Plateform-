package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.QueryHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface QueryHistoryRepository extends MongoRepository<QueryHistory, String> {
}
