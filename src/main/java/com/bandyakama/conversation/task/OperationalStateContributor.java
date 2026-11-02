package com.bandyakama.conversation.task;

import com.bandyakama.chat.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Extension point for capability-specific operational state enrichment.
 * The dialogue/task lifecycle remains domain-neutral; optional contributors
 * may preserve narrowly scoped data required by a registered capability.
 */
public interface OperationalStateContributor {
    boolean supports(ChatResponse.ToolState update);

    void contribute(
            ObjectNode nextState,
            JsonNode previousState,
            ChatResponse.ToolState update
    );
}
