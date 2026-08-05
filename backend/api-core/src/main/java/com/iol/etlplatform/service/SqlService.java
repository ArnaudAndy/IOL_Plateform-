package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.sql.ExecuteSqlRequest;
import com.iol.etlplatform.dto.sql.ExecuteSqlResponse;
import com.iol.etlplatform.dto.workflow.ValidateSqlRequest;
import com.iol.etlplatform.dto.workflow.ValidateSqlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SqlService {

    private final SqlValidationService sqlValidationService;
    private final SqlWorkbenchService sqlWorkbenchService;

    public ValidateSqlResponse validate(ValidateSqlRequest request) {
        return sqlValidationService.validate(request);
    }

    public ExecuteSqlResponse execute(ExecuteSqlRequest request) {
        return sqlWorkbenchService.execute(request);
    }
}
