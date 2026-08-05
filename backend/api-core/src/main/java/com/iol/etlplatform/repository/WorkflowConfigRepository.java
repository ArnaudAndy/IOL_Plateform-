package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WorkflowConfigRepository extends MongoRepository<WorkflowConfig, String> {
    List<WorkflowConfig> findByCreatedBy(String createdBy);
    List<WorkflowConfig> findByStandardIdAndDirectionAndActiveTrue(String standardId, WorkflowDirection direction);
}
