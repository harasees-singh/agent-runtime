package io.agentruntime.core.domain.execution;

public record RecoveryResult(ExecutionRef ref, boolean resolved) {
    public static RecoveryResult unresolved() { return new RecoveryResult(null, false); }
    public static RecoveryResult found(ExecutionRef ref) { return new RecoveryResult(ref, true); }
}
