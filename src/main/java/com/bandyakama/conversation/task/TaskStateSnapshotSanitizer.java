package com.bandyakama.conversation.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies an explicit, fail-closed allowlist before operational data enters a
 * durable task snapshot. Conversation memory may contain richer data, but a
 * snapshot is only a resumable hint and must not become a second domain data store.
 */
final class TaskStateSnapshotSanitizer {
    private static final Set<String> SAFE_AGENT_FILTER_FIELDS = Set.of(
            "service",
            "direction",
            "poste",
            "categorie",
            "corps",
            "type_emploi",
            "classe",
            "echelon"
    );

    private static final Set<String> SAFE_FILTER_OPERATORS = Set.of(
            "EQ", "NE", "CONTAINS", "FUZZY",
            "GT", "GTE", "LT", "LTE", "BETWEEN"
    );

    private static final Set<String> SAFE_ORGANIZATION_SCOPES = Set.of(
            "ALL", "DIRECTION", "SERVICE", "POSTE"
    );

    private final ObjectMapper objectMapper;

    TaskStateSnapshotSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode sanitizeOperationalState(JsonNode state) {
        return sanitizeOperationalState(state, null);
    }

    ObjectNode sanitizeOperationalState(JsonNode state, ToolDefinition definition) {
        ObjectNode safe = objectMapper.createObjectNode();
        if (state == null || !state.isObject()) {
            return safe;
        }

        String toolName = state.path("toolName").asText("").trim();
        if (toolName.isBlank()) {
            return safe;
        }

        safe.put("toolName", toolName);
        String intent = state.path("intent").asText("");
        if ("TOOL_CALL".equals(intent)) {
            safe.put("intent", intent);
        }

        ObjectNode safeArguments = sanitizeArguments(
                toolName,
                state.path("arguments"),
                definition
        );
        if (!safeArguments.isEmpty()) {
            safe.set("arguments", safeArguments);
        }

        if ("search_agents".equals(toolName)) {
            JsonNode criteriaJson = safeArguments.path("criteriaJson");
            if (criteriaJson.isTextual()) {
                try {
                    JsonNode criteria = objectMapper.readTree(criteriaJson.textValue());
                    if (criteria.isObject()) {
                        safe.set("criteria", criteria);
                    }
                } catch (JsonProcessingException ignored) {
                    // Invalid criteria are omitted; the operation must be rebuilt.
                }
            }
        }
        return safe;
    }

    ObjectNode sanitizeArguments(String toolName, JsonNode arguments) {
        return sanitizeArguments(toolName, arguments, null);
    }

    ObjectNode sanitizeArguments(
            String toolName,
            JsonNode arguments,
            ToolDefinition definition
    ) {
        ObjectNode safe = objectMapper.createObjectNode();
        if (arguments == null || !arguments.isObject()
                || (definition != null && definition.isSensitive())) {
            return safe;
        }

        // RH-specific capabilities retain their reviewed allowlists. Other
        // capabilities require a configured, explicitly non-sensitive definition.
        if ("search_agents".equals(toolName)) {
            String resultMode = arguments.path("resultMode").asText("")
                    .trim().toUpperCase(Locale.ROOT);
            if ("LIST".equals(resultMode) || "COUNT".equals(resultMode)) {
                safe.put("resultMode", resultMode);
            }

            JsonNode rawCriteria = arguments.path("criteriaJson");
            if (rawCriteria.isTextual()) {
                try {
                    ObjectNode criteria = sanitizeAgentCriteria(
                            objectMapper.readTree(rawCriteria.textValue())
                    );
                    if (criteria.path("filters").size() > 0) {
                        safe.put("criteriaJson", objectMapper.writeValueAsString(criteria));
                    }
                } catch (JsonProcessingException ignored) {
                    // Malformed or non-JSON criteria are not persisted.
                }
            }
        } else if ("get_organization_info".equals(toolName)) {
            // The free-text query is intentionally omitted because it could
            // contain an employee name or another personal identifier.
            String scope = arguments.path("scope").asText("")
                    .trim().toUpperCase(Locale.ROOT);
            if (SAFE_ORGANIZATION_SCOPES.contains(scope)) {
                safe.put("scope", scope);
            }
        } else if (definition != null && !definition.isSensitive()) {
            // Generic non-sensitive capabilities may retain only declared,
            // scalar parameters. Unknown, nested and identifier-like values
            // remain excluded from durable snapshots.
            List<ToolParameter> declaredParameters = definition.getParameters() == null
                    ? List.of() : definition.getParameters();
            for (ToolParameter parameter : declaredParameters) {
                JsonNode value = arguments.path(parameter.getName());
                if (value == null || value.isMissingNode() || value.isNull()
                        || !value.isValueNode()) {
                    continue;
                }
                if (value.isTextual()) {
                    String text = value.textValue().trim();
                    if (text.isBlank() || text.length() > 256
                            || text.startsWith("{") || text.startsWith("[")
                            || looksLikeDirectIdentifier(text)) {
                        continue;
                    }
                }
                safe.set(parameter.getName(), value.deepCopy());
            }
        }
        return safe;
    }

    private ObjectNode sanitizeAgentCriteria(JsonNode rawCriteria) {
        ObjectNode safe = objectMapper.createObjectNode();
        ArrayNode safeFilters = objectMapper.createArrayNode();
        JsonNode filters = rawCriteria == null ? null : rawCriteria.path("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode filter : filters) {
                JsonNode safeFilter = sanitizeAgentFilter(filter);
                if (safeFilter != null) {
                    safeFilters.add(safeFilter);
                    if (safeFilters.size() == 10) {
                        break;
                    }
                }
            }
        }
        safe.set("filters", safeFilters);

        int limit = rawCriteria == null ? 0 : rawCriteria.path("limit").asInt(0);
        if (limit >= 1 && limit <= 50) {
            safe.put("limit", limit);
        }
        int offset = rawCriteria == null ? -1 : rawCriteria.path("offset").asInt(-1);
        if (offset >= 0 && offset <= 1_000_000) {
            safe.put("offset", offset);
        }
        return safe;
    }

    private ObjectNode sanitizeAgentFilter(JsonNode filter) {
        if (filter == null || !filter.isObject()) {
            return null;
        }
        String field = filter.path("field").asText("")
                .trim().toLowerCase(Locale.ROOT);
        String operator = filter.path("operator").asText("")
                .trim().toUpperCase(Locale.ROOT);
        if (!SAFE_AGENT_FILTER_FIELDS.contains(field)
                || !SAFE_FILTER_OPERATORS.contains(operator)) {
            return null;
        }

        JsonNode value = filter.path("value");
        if (value.isMissingNode() || value.isNull()
                || !(value.isValueNode())) {
            return null;
        }
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (text.isBlank() || text.length() > 120
                    || looksLikeDirectIdentifier(text)) {
                return null;
            }
        }

        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("field", field);
        safe.put("operator", operator);
        safe.set("value", value.deepCopy());
        return safe;
    }

    private boolean looksLikeDirectIdentifier(String value) {
        if (value.contains("@")) {
            return true;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() >= 8 && digits.length() <= 15) {
            return true;
        }
        return value.matches("(?i)^(?:matricule|cin|cni|nif)\\s*[:#-]?\\s*.+$");
    }
}
