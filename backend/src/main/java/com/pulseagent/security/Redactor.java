package com.pulseagent.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes secrets and personal data from text before it is stored, logged or sent to the LLM.
 * Telemetry, stack traces and uploaded documents are all treated as untrusted input.
 */
public final class Redactor {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 [REDACTED]"),
            new Rule(Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|authorization|client[_-]?secret)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)"), "$1$2[REDACTED]"),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[REDACTED_AWS_KEY]"),
            new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), "[REDACTED_GOOGLE_KEY]"),
            new Rule(Pattern.compile("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\\b"), "[REDACTED_GITHUB_TOKEN]"),
            new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"), "[REDACTED_GITHUB_TOKEN]"),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"), "[REDACTED_API_KEY]"),
            new Rule(Pattern.compile("\\bpa_(admin|ingest)_[A-Za-z0-9_-]{16,}\\b"), "[REDACTED_PULSEAGENT_KEY]"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"), "[REDACTED_JWT]"),
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), "[REDACTED_PRIVATE_KEY]"),
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), "[REDACTED_NUMBER]"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
    );

    private Redactor() {}

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = input;
        for (Rule r : RULES) out = r.pattern().matcher(out).replaceAll(r.replacement());
        return out;
    }
}
