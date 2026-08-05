package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.sql.ExecuteSqlRequest;
import com.iol.etlplatform.dto.sql.ExecuteSqlResponse;
import com.iol.etlplatform.dto.workflow.ValidateSqlRequest;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SqlWorkbenchService {

    private final SqlValidationService sqlValidationService;
    private final DestinationConnectionService connectionService;
    private final WorkflowConfigRepository workflowRepository;

    public ExecuteSqlResponse execute(ExecuteSqlRequest request) {
        ValidateSqlRequest validateRequest = new ValidateSqlRequest();
        validateRequest.setSqlScript(request.getSqlScript());
        validateRequest.setWorkflowStep("SILVER");
        if (!sqlValidationService.validate(validateRequest).isValid()) {
            throw new BadRequestException("Le SQL doit etre une requete SELECT valide avant execution.");
        }

        String connectionId = resolveConnectionId(request);
        DestinationConnection target = connectionService.getEntityById(connectionId);
        int limit = request.getLimit() == null ? 100 : Math.max(1, Math.min(request.getLimit(), 1000));
        long startedAt = System.nanoTime();

        try {
            connectionService.registerDriver(target.getDbType());
            String jdbcUrl = connectionService.buildJdbcUrl(target);
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl, target.getUsername(), connectionService.resolvePassword(target));
                 PreparedStatement statement = connection.prepareStatement(request.getSqlScript())) {
                statement.setMaxRows(limit);
                statement.setQueryTimeout(30);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        columns.add(metadata.getColumnLabel(index));
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next() && rows.size() < limit) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= columns.size(); index++) {
                            row.put(columns.get(index - 1), resultSet.getObject(index));
                        }
                        rows.add(row);
                    }
                    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                    return new ExecuteSqlResponse(true, columns, rows, rows.size(), elapsedMs, null);
                }
            }
        } catch (Exception e) {
            throw new BadRequestException("Execution SQL impossible: " + e.getMessage());
        }
    }

    private String resolveConnectionId(ExecuteSqlRequest request) {
        if (request.getConnectionId() != null && !request.getConnectionId().isBlank()) {
            return request.getConnectionId();
        }
        if (request.getWorkflowId() != null && !request.getWorkflowId().isBlank()) {
            return workflowRepository.findById(request.getWorkflowId())
                    .map(workflow -> workflow.getDestinationConnectionId())
                    .filter(id -> id != null && !id.isBlank())
                    .orElseThrow(() -> new BadRequestException(
                            "Ce workflow n'a pas de connexion de destination."));
        }
        throw new BadRequestException("Choisissez une connexion pour executer la requete.");
    }
}
