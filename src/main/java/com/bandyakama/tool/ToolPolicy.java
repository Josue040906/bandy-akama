package com.bandyakama.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single policy used both when exposing functions to Gemini and when accepting
 * a tool execution request. Approval and activation are separate controls.
 */
public final class ToolPolicy {
    private static final List<String> RESERVED_CONVERSATION_TOOLS = List.of(
            "clearConversationState",
            "reset_conversation_context"
    );

    private ToolPolicy() {
    }

    public static boolean isUsableForGemini(ToolDefinition tool) {
        if (tool == null
                || !tool.isApprovedForGemini()
                || !tool.isEnabled()
                || RESERVED_CONVERSATION_TOOLS.contains(tool.getName())) {
            return false;
        }

        try {
            tool.validate();
            return true;
        } catch (IllegalArgumentException invalidDefinition) {
            return false;
        }
    }

    public static List<ToolDefinition> selectUsableForGemini(
            Collection<ToolDefinition> availableTools
    ) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }

        Map<String, ToolDefinition> usableByName = new LinkedHashMap<>();
        availableTools.stream()
                .filter(ToolPolicy::isUsableForGemini)
                .forEach(tool -> usableByName.putIfAbsent(
                        tool.getName(),
                        tool
                ));
        return List.copyOf(usableByName.values());
    }
}
