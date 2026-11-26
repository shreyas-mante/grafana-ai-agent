package com.pulseagent.errors;

import java.time.Instant;
import java.util.List;

/** Occurrences of the same error (same service, exception type and top application frame). */
public record ErrorGroup(String id, String service, String environment, String exceptionClass, String message,
                         String httpMethod, String path, String commitSha, long count, Instant firstSeen, Instant lastSeen,
                         List<ErrorEventRequest.Frame> frames, List<ErrorEventRequest.Cause> causes) {}
