package com.bandyakama.conversation;

import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.ContextInterpretationProposal;
import com.bandyakama.conversation.dialogue.ConversationPlanInterpreter;
import com.bandyakama.conversation.dialogue.ConversationPlanProposal;
import com.bandyakama.conversation.dialogue.DialogueInterpreter;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.conversation.dialogue.PendingClarification;

import java.util.List;

/**
 * Temporary test-only bridge while integration fixtures migrate from the
 * former single-decision interpreter to the multi-operation plan contract.
 *
 * <p>Production always uses GeminiConversationPlanInterpreter. This adapter
 * exists only in tests and ensures that the real context engine, validator
 * and transitioner are still exercised without a Gemini credential.</p>
 */
public final class LegacyDialoguePlanTestConfiguration {

    private LegacyDialoguePlanTestConfiguration() { }

    public static ConversationPlanProposal adapt(
            ContextEngineInput input,
            ContextInterpretationProposal legacy
    ) {
        var operations = new java.util.ArrayList<ConversationPlanProposal.Operation>();
        var clarifications = new java.util.ArrayList<ConversationPlanProposal.Clarification>();

        switch (legacy.relation()) {
            case NEW_TASK -> operations.add(operation(
                    input, "create", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                    null, legacy.objectiveGoal(), legacy.objectiveSummary(),
                    null, legacy.referenceMentions(), null
            ));
            case CONTINUATION -> operations.add(operation(
                    input, "continue", ConversationPlanProposal.OperationType.CONTINUE_OBJECTIVE,
                    legacy.targetObjectiveId(), null, legacy.objectiveSummary(),
                    null, legacy.referenceMentions(), null
            ));
            case TOPIC_RESUMPTION -> operations.add(operation(
                    input, "resume", ConversationPlanProposal.OperationType.RESUME_OBJECTIVE,
                    legacy.targetObjectiveId(), null, legacy.objectiveSummary(),
                    null, legacy.referenceMentions(), null
            ));
            case CORRECTION -> operations.add(operation(
                    input, "correct", ConversationPlanProposal.OperationType.CORRECT_OBJECTIVE,
                    legacy.targetObjectiveId(), legacy.objectiveGoal(), legacy.objectiveSummary(),
                    null, legacy.referenceMentions(), null
            ));
            case TASK_COMPLETION -> operations.add(operation(
                    input, "complete", ConversationPlanProposal.OperationType.COMPLETE_OBJECTIVE,
                    legacy.targetObjectiveId(), null, null, null, List.of(), null
            ));
            case USER_CLARIFICATION -> {
                PendingClarification pending = findPending(input, legacy.targetObjectiveId());
                String clarificationId = pending == null
                        ? "missing-clarification-" + input.currentMessageSequenceNumber()
                        : pending.clarificationId();
                operations.add(operation(
                        input, "answer-clarification",
                        ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                        legacy.targetObjectiveId(), null, legacy.objectiveSummary(), null,
                        legacy.referenceMentions(), clarificationId
                ));
            }
            case NEEDS_CLARIFICATION -> {
                var target = legacy.targetObjectiveId() == null ? null
                        : new ConversationPlanProposal.EntityReference(
                                ConversationPlanProposal.ReferenceScope.EXISTING_OBJECTIVE,
                                legacy.targetObjectiveId()
                        );
                clarifications.add(new ConversationPlanProposal.Clarification(
                        "test-clarification-" + input.currentMessageSequenceNumber(),
                        target,
                        legacy.clarificationQuestion(),
                        legacy.rationale()
                ));
            }
            case SOCIAL_TURN -> operations.add(operation(
                    input, "social", ConversationPlanProposal.OperationType.SOCIAL_TURN,
                    null, null, null, null, List.of(), null
            ));
            case NEW_TOPIC_REQUEST -> operations.add(operation(
                    input, "new-topic", ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST,
                    null, null, null, null, List.of(), null
            ));
        }

        return new ConversationPlanProposal(
                legacy.rationale(),
                legacy.confidence(),
                operations,
                clarifications
        );
    }

    private static PendingClarification findPending(ContextEngineInput input, String targetObjectiveId) {
        return input.dialogueState().pendingClarifications().stream()
                .filter(item -> targetObjectiveId == null
                        || targetObjectiveId.equals(item.targetObjectiveId()))
                .findFirst()
                .orElse(null);
    }

    private static ConversationPlanProposal.Operation operation(
            ContextEngineInput input,
            String suffix,
            ConversationPlanProposal.OperationType type,
            String target,
            String goal,
            String summary,
            ConversationPlanProposal.EntityReference parent,
            List<com.bandyakama.conversation.dialogue.ProposedDialogueReference> references,
            String clarificationId
    ) {
        return new ConversationPlanProposal.Operation(
                suffix + "-" + input.currentMessageSequenceNumber(),
                type,
                target,
                goal,
                summary,
                parent,
                List.of(),
                references,
                clarificationId
        );
    }
}
