package com.bandyakama.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPolicyTest {
    @Test
    void exposesAnyValidToolExplicitlyApprovedAndEnabled() {
        ToolDefinition approved = tool("new_hr_lookup");
        approved.setApprovedForGemini(true);

        assertTrue(ToolPolicy.isUsableForGemini(approved));
        assertEquals(
                List.of(approved),
                ToolPolicy.selectUsableForGemini(List.of(approved))
        );
    }

    @Test
    void rejectsToolsThatAreNotApprovedOrAreDisabled() {
        ToolDefinition unapproved = tool("unapproved_lookup");
        ToolDefinition disabled = tool("disabled_lookup");
        disabled.setApprovedForGemini(true);
        disabled.setEnabled(false);

        assertFalse(ToolPolicy.isUsableForGemini(unapproved));
        assertFalse(ToolPolicy.isUsableForGemini(disabled));
        assertTrue(ToolPolicy.selectUsableForGemini(
                List.of(unapproved, disabled)
        ).isEmpty());
    }

    @Test
    void rejectsInvalidDefinitionsAndReservedConversationActions() {
        ToolDefinition invalid = tool("invalid_lookup");
        invalid.setApprovedForGemini(true);
        invalid.setUrl("file:///etc/passwd");

        ToolDefinition reserved = tool("reset_conversation_context");
        reserved.setApprovedForGemini(true);

        assertFalse(ToolPolicy.isUsableForGemini(invalid));
        assertFalse(ToolPolicy.isUsableForGemini(reserved));
    }

    @Test
    void removesDuplicateNamesWithoutChangingFirstApprovedDefinition() {
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
        tool.setWhenToUse("Used by policy tests.");
        return tool;
    }
}
