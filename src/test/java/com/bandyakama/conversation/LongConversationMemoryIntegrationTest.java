package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.ContextInterpretationProposal;
import com.bandyakama.conversation.dialogue.ConversationPlanInterpreter;
import com.bandyakama.conversation.dialogue.DialogueInterpreter;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.conversation.dialogue.ProposedDialogueReference;
import com.bandyakama.security.UserIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Long-conversation regression: durable dialogue facts outlive the recent-history window. */
@SpringBootTest(properties = {
        "bandy.conversations.allowed-application-ids=client-a",
        "bandy.conversations.context.recent-messages=10",
        "bandy.conversations.dialogue.enabled=true"
})
class LongConversationMemoryIntegrationTest {

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private DialogueStateRepository dialogueStateRepository;

    @MockBean
    private DialogueInterpreter dialogueInterpreter;

    @MockBean
    private ConversationPlanInterpreter planInterpreter;

    @MockBean
    private GeminiAssistant assistant;

    @Test
    void keepsObjectiveAndOldReferenceAvailableAfterSixtySocialTurns() {
        UserIdentity identity = new UserIdentity("long-memory-owner", "client-a");
        Conversation conversation = conversationRepository.create(
                identity.applicationId(), identity.ownerId()
        );

        when(assistant.respond(
                anyString(), nullable(String.class), anyString(), any(UUID.class),
                nullable(JsonNode.class), nullable(Consumer.class), anyMap()
        )).thenReturn(
                new ChatResponse("Le rapport est prêt.", List.of()),
                new ChatResponse("Le rapport reprend la version initiale.", List.of())
        );

        when(planInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    return LegacyDialoguePlanTestConfiguration.adapt(
                            input, dialogueInterpreter.interpret(input)
                    );
                });

        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    if (input.currentMessage().equals("Prépare le rapport avec la version initiale.")) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "Création du rapport et mémorisation de sa version initiale.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un rapport",
                                "Le rapport utilise la version initiale.",
                                List.of(new ProposedDialogueReference("version initiale", "version"))
                        );
                    }
                    if (input.currentMessage().equals("Reprends le rapport et utilise sa version initiale.")) {
                        assertNotNull(input.dialogueState().activeObjectiveId());
                        assertEquals(1, input.dialogueState().objectives().size());
                        assertEquals(
                                "version initiale",
                                input.dialogueState().objectives().get(0).references().get(0).label()
                        );
                        return new ContextInterpretationProposal(
                                DialogueRelation.CONTINUATION,
                                input.dialogueState().activeObjectiveId(),
                                "L'utilisateur reprend le rapport en désignant sa version initiale.",
                                null,
                                InterpretationConfidence.HIGH,
                                null,
                                "Le rapport reprend explicitement la version initiale.",
                                List.of()
                        );
                    }
                    return new ContextInterpretationProposal(
                            DialogueRelation.SOCIAL_TURN,
                            null,
                            "Échange social sans modification de l'objectif.",
                            null,
                            InterpretationConfidence.HIGH,
                            null,
                            null,
                            List.of()
                    );
                });

        conversationService.sendMessage(
                conversation.id(), identity,
                "Prépare le rapport avec la version initiale.", null, UUID.randomUUID()
        );

        for (int turn = 1; turn <= 60; turn++) {
            conversationService.sendMessage(
                    conversation.id(), identity,
                    "Échange social numéro " + turn + " autour de la conversation.",
                    null, UUID.randomUUID()
            );
        }

        DialogueState retained = dialogueStateRepository.load(conversation.id());
        assertEquals(1, retained.objectives().size());
        assertEquals(
                "version initiale",
                retained.objectives().get(0).references().get(0).label()
        );

        conversationService.sendMessage(
                conversation.id(), identity,
                "Reprends le rapport et utilise sa version initiale.", null, UUID.randomUUID()
        );

        DialogueState resumed = dialogueStateRepository.load(conversation.id());
        assertEquals(retained.activeObjectiveId(), resumed.activeObjectiveId());
        assertEquals(
                "Le rapport reprend explicitement la version initiale.",
                resumed.objectives().get(0).summary()
        );
        verify(dialogueInterpreter, times(62)).interpret(any(ContextEngineInput.class));
    }
}
