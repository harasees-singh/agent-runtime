package io.agentruntime.app;

import io.agentruntime.core.domain.execution.AgentEngine;
import io.agentruntime.core.domain.execution.AgentModel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "runtime.agents")
public record AgentConfigurationProperties(AgentEngine defaultEngine, Map<String, Engine> engines) {
    public record Engine(AgentModel defaultModel, Map<String, String> models, String taskDefinition, String container) {}

    public Map<AgentEngine, AgentModel> defaultModels() {
        return engines.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> AgentEngine.fromId(entry.getKey()), entry -> entry.getValue().defaultModel()));
    }

    public Map<AgentEngine, Map<AgentModel, String>> modelIds() {
        return engines.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> AgentEngine.fromId(entry.getKey()), entry -> entry.getValue().models().entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                model -> AgentModel.fromId(model.getKey()), Map.Entry::getValue))));
    }

    public Map<AgentEngine, Engine> enginesById() {
        return engines.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> AgentEngine.fromId(entry.getKey()), Map.Entry::getValue));
    }
}
