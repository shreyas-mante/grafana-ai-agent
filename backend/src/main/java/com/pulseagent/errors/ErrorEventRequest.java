package com.pulseagent.errors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Error event posted by the PulseAgent SDK. Every field is bounded and pattern-checked. */
public record ErrorEventRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String service,
        @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9._-]*") String environment,
        @Size(max = 40) @Pattern(regexp = "([0-9a-f]{7,40})?") String commitSha,
        @NotBlank @Size(max = 300) @Pattern(regexp = "[\\w.$]+") String exceptionClass,
        @Size(max = 4000) String message,
        @Size(max = 10) @Pattern(regexp = "[A-Z]*") String httpMethod,
        @Size(max = 300) String path,
        @NotNull @Size(max = 60) List<@Valid Frame> frames,
        @Size(max = 5) List<@Valid Cause> causes) {

    public record Frame(
            @NotBlank @Size(max = 300) @Pattern(regexp = "[\\w.$]+") String className,
            @Size(max = 200) @Pattern(regexp = "[\\w$<>-]*") String methodName,
            @Size(max = 200) @Pattern(regexp = "[\\w.$-]*") String fileName,
            @Min(-2) @Max(10_000_000) int lineNumber) {}

    public record Cause(
            @NotBlank @Size(max = 300) @Pattern(regexp = "[\\w.$]+") String exceptionClass,
            @Size(max = 2000) String message) {}
}
