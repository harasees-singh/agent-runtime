package io.agentruntime.core.exception;

public class LaunchUnknownException extends RuntimeException {
    public LaunchUnknownException(String message, Throwable cause) { super(message, cause); }
    public LaunchUnknownException(String message) { super(message); }
}
