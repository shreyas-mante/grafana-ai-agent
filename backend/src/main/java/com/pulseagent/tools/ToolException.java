package com.pulseagent.tools;

/** A tool failure whose message is safe to show to the model and the user. */
public class ToolException extends RuntimeException {
    public ToolException(String message) { super(message); }
}
