package com.bandyakama.conversation;

import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lit, une fois par conversation, le contexte de l'utilisateur connecté (par
 * exemple son prénom) avec l'outil que le catalogue désigne par
 * userContextFields. Seuls ces champs sont gardés. Une panne ne bloque jamais
 * la réponse : la conversation continue sans ce contexte.
 */
@Service
public class UserContextService {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserContextService.class);

    private final ToolRepository toolRepository;
    private final ToolExecutor toolExecutor;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserContextService(
            ToolRepository toolRepository,
            ToolExecutor toolExecutor,
            ConversationRepository conversationRepository
    ) {
        this.toolRepository = toolRepository;
        this.toolExecutor = toolExecutor;
        this.conversationRepository = conversationRepository;
    }

    public void ensureLoaded(UUID conversationId, String authorization, UUID requestId) {
        if (conversationRepository.hasUserContext(conversationId)) {
            return;
        }
        Optional<ToolDefinition> tool = toolRepository.findEnabled().stream()
                .filter(candidate -> !candidate.getUserContextFields().isEmpty())
                .findFirst();
        if (tool.isEmpty()) {
            return;
        }
        ObjectNode kept = objectMapper.createObjectNode();
        try {
            JsonNode result = objectMapper.valueToTree(toolExecutor.execute(
                    tool.get(),
                    Map.of(),
                    authorization,
                    requestId.toString()
            ));
            for (String field : tool.get().getUserContextFields()) {
                JsonNode value = result == null ? null : result.get(field);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    kept.set(field, value);
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Contexte utilisateur indisponible via {} : {}",
                    tool.get().getName(),
                    failure.getClass().getSimpleName()
            );
        }
        conversationRepository.saveUserContext(conversationId, kept);
    }
}
