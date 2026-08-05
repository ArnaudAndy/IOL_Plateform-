package com.iol.openhim.runtime;

import java.util.List;

public class DomainValidationException extends RuntimeException {

    private final List<String> issues;

    public DomainValidationException(String message) {
        this(message, List.of(message));
    }

    public DomainValidationException(String message, List<String> issues) {
        super(message);
        this.issues = issues == null ? List.of(message) : List.copyOf(issues);
    }

    public List<String> getIssues() {
        return issues;
    }
}
