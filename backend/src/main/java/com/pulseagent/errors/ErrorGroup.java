package com.pulseagent.errors;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Occurrences of the same error (same service, exception type and top application frame). */
public class ErrorGroup {
    public final String id;
    public final String service;
    public final String exceptionClass;
    public final Instant firstSeen;
    public volatile Instant lastSeen;
    public final AtomicLong count = new AtomicLong();
    public volatile String environment;
    public volatile String commitSha;
    public volatile String message;
    public volatile String httpMethod;
    public volatile String path;
    public volatile List<ErrorEventRequest.Frame> frames;
    public volatile List<ErrorEventRequest.Cause> causes;

    public ErrorGroup(String id, String service, String exceptionClass) {
        this.id = id;
        this.service = service;
        this.exceptionClass = exceptionClass;
        this.firstSeen = Instant.now();
        this.lastSeen = firstSeen;
    }
}
