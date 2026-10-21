package com.bandyakama.assistant;

import com.bandyakama.tool.ToolDefinition;

import java.util.List;

public record AssistantConfigurationSnapshot(
        AssistantProfile profile,
        List<ToolDefinition> enabledTools
) {
    public AssistantConfigurationSnapshot {
        enabledTools = List.copyOf(enabledTools);
    }
}
