package com.bandyakama.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPolicyTest {
    @Test
    void exposesAnyValidToolWhenItIsExplicitlyApprovedAndEnabled() {
        ToolDefinition tool = tool("lookup_customer");
        tool.setApprovedForGemini(true);

        assertTrue(ToolPolicy.isUsableForGemini(tool));
        assertEquals(
                List.of(tool),
                ToolPolicy.selectUsableForGemini(List.of(tool))
        );
    }

    @Test
    void doesNotExposeUnapprovedDisabledInvalidOrReservedTools() {
        ToolDefinition unapproved = tool("unapproved_lookup");

        ToolDefinition disabled = tool("disabled_lookup");
        disabled.setApprovedForGemini(true);
        disabled.setEnabled(false);

        ToolDefinition invalid = tool("invalid_lookup");
        invalid.setApprovedForGemini(true);
        invalid.setUrl("file:///etc/passwd");

        ToolDefinition reserved = tool("reset_conversation_context");
        reserved.setApprovedForGemini(true);

        assertFalse(ToolPolicy.isUsableForGemini(unapproved));
        assertFalse(ToolPolicy.isUsableForGemini(disabled));
        assertFalse(ToolPolicy.isUsableForGemini(invalid));
        assertFalse(ToolPolicy.isUsableForGemini(reserved));
        assertTrue(ToolPolicy.selectUsableForGemini(
                List.of(unapproved, disabled, invalid, reserved)
        ).isEmpty());
    }

    @Test
    void removesDuplicateToolNamesDeterministically() {
        ToolDefinition first = tool("same_lookup");
        first.setApprovedForGemini(true);
        ToolDefinition duplicate = tool("same_lookup");
        duplicate.setApprovedForGemini(true);
        duplicate.setDescription("Different duplicate");

        assertEquals(
                List.of(first),
                ToolPolicy.selectUsableForGemini(List.of(first, duplicate))
        );
    }

    private ToolDefinition tool(String name) {
        ToolDefinition tool = new ToolDefinition(
                name,
                "Tool description",
                "GET",
                "https://example.test/api",
                List.of()
        );
        tool.setWhenToUse("Use when this capability is needed.");
        return tool;
    }
}
