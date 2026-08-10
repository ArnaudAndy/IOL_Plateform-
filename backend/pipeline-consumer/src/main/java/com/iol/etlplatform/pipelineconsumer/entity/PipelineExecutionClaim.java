package com.iol.etlplatform.pipelineconsumer.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Etat idempotent partage d'une commande de pipeline. */
@Document(collection = "pipeline_execution_claims")
public class PipelineExecutionClaim {

    @Id
    private String executionLogId;
    private String workflowId;
    private String commandHash;
    private String owner;
    private String fencingToken;
    private State state;
    private int attempts;
    private Instant claimedAt;
    private Instant leaseExpiresAt;
    private Instant completedAt;
    private Boolean success;
    private String logOutput;
    private String errorMessage;
    private long durationMs;
    private String failureReason;

    public PipelineExecutionClaim() {
    }

    public String getExecutionLogId() { return executionLogId; }
    public void setExecutionLogId(String value) { this.executionLogId = value; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String value) { this.workflowId = value; }
    public String getCommandHash() { return commandHash; }
    public void setCommandHash(String value) { this.commandHash = value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { this.owner = value; }
    public String getFencingToken() { return fencingToken; }
    public void setFencingToken(String value) { this.fencingToken = value; }
    public State getState() { return state; }
    public void setState(State value) { this.state = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { this.attempts = value; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant value) { this.claimedAt = value; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant value) { this.leaseExpiresAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { this.completedAt = value; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean value) { this.success = value; }
    public String getLogOutput() { return logOutput; }
    public void setLogOutput(String value) { this.logOutput = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { this.errorMessage = value; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long value) { this.durationMs = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }

    public enum State {
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }
}
