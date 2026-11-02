package com.bandyakama.conversation.task;

import com.bandyakama.chat.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * RH capability extension that keeps a small bounded cache of agent profiles.
 * This policy is deliberately outside the domain-neutral task-state manager.
 */
@Component
public class AgentProfileStateContributor implements OperationalStateContributor {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AgentProfileStateContributor.class);
    private static final int MAX_CACHED_PROFILE_CHARACTERS = 8_000;
    private static final int MAX_CACHED_PROFILES = 3;

    private final ObjectMapper objectMapper;

    public AgentProfileStateContributor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public boolean supports(ChatResponse.ToolState update) {
        // Carry the bounded cache across unrelated tool calls too.
        return update != null;
    }

    @Override
    public void contribute(
            ObjectNode nextState,
            JsonNode previousState,
            ChatResponse.ToolState update
    ) {
        ObjectNode profiles = previousState != null
                && previousState.path("profiles").isObject()
                ? ((ObjectNode) previousState.path("profiles")).deepCopy()
                : objectMapper.createObjectNode();

        if ("get_agent_profile".equals(update.name()) && update.result() != null) {
            JsonNode profile = objectMapper.valueToTree(update.result());
            String serialized = writeJson(profile);
            if (serialized.length() <= MAX_CACHED_PROFILE_CHARACTERS) {
                profiles.set(profileCacheKey(update.arguments(), profile), profile);
                while (profiles.size() > MAX_CACHED_PROFILES) {
                    profiles.remove(profiles.fieldNames().next());
                }
            } else {
                LOGGER.warn(
                        "Assistant profile cache skipped because the result exceeded {} characters (actual: {}).",
                        MAX_CACHED_PROFILE_CHARACTERS,
                        serialized.length()
                );
            }
        }

        if (!profiles.isEmpty()) {
            nextState.set("profiles", profiles);
        }
    }

    private String profileCacheKey(
            Map<String, Object> arguments,
            JsonNode profile
    ) {
        String matricule = profile.path("agent").path("matricule").asText("");
        String identity = matricule.isBlank()
                ? String.valueOf(arguments == null
                        ? ""
                        : arguments.getOrDefault("nom_agent", ""))
                : matricule;
        return identity.trim().toLowerCase(Locale.ROOT);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser le cache de profil agent.",
                    exception
            );
        }
    }
}
