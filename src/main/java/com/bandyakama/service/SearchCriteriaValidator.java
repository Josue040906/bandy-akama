package com.bandyakama.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SearchCriteriaValidator {
    private SearchCriteriaValidator() {
    }

    static boolean hasPositiveCriteria(Object criteriaValue, ObjectMapper mapper) {
        if (criteriaValue == null) return false;
        try {
            JsonNode criteria = criteriaValue instanceof String text
                    ? mapper.readTree(text) : mapper.valueToTree(criteriaValue);
            if (criteria == null || !criteria.isObject()) return false;
            if (hasPositiveGroup(criteria.path("filters"))
                    || hasPositiveGroup(criteria.path("must"))) return true;
            return criteria.path("minimum_should_match").asInt(0) >= 1
                    && hasPositiveGroup(criteria.path("should"));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static boolean hasPositiveGroup(JsonNode group) {
        if (!group.isArray()) return false;
        for (JsonNode condition : group) {
            if (hasPositiveCondition(condition)) return true;
        }
        return false;
    }

    private static boolean hasPositiveCondition(JsonNode condition) {
        String field = condition.path("field").asText("");
        String operator = condition.path("operator").asText("");
        JsonNode value = condition.get("value");
        if (!field.isBlank() && !operator.isBlank() && value != null
                && !value.isNull()
                && (!value.isTextual() || !value.asText().isBlank())) return true;
        JsonNode nested = condition.path("bool");
        return nested.isObject()
                && (hasPositiveGroup(nested.path("must"))
                || (nested.path("minimum_should_match").asInt(0) >= 1
                && hasPositiveGroup(nested.path("should"))));
    }
}
