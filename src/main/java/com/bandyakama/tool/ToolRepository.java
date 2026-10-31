package com.bandyakama.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ToolRepository {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RowMapper<ToolDefinition> TOOL_ROW_MAPPER =
            (resultSet, rowNumber) -> {
                ToolDefinition tool = new ToolDefinition(
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getString("http_method"),
                        resultSet.getString("endpoint_url"),
                        List.of()
                );
                tool.setWhenToUse(resultSet.getString("when_to_use"));
                tool.setEnabled(resultSet.getBoolean("enabled"));
                tool.setAssistantEnabled(resultSet.getBoolean("assistant_enabled"));
                tool.setSensitive(resultSet.getBoolean("sensitive"));
                tool.setResultEntityType(resultSet.getString("result_entity_type"));
                tool.setResultCollectionKey(resultSet.getString("result_collection_key"));
                return tool;
            };

    private static final RowMapper<ToolParameter> PARAMETER_ROW_MAPPER =
            (resultSet, rowNumber) -> {
                ToolParameter parameter = new ToolParameter(
                        resultSet.getString("name"),
                        resultSet.getString("type"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("required")
                );
                parameter.setLocation(resultSet.getString("location"));
                parameter.setReferenceType(resultSet.getString("reference_type"));
                String allowedValues = resultSet.getString("allowed_values");
                if (allowedValues != null && !allowedValues.isBlank()) {
                    try {
                        parameter.setAllowedValues(OBJECT_MAPPER.readValue(
                                allowedValues, new TypeReference<List<String>>() {}
                        ));
                    } catch (java.io.IOException invalidValues) {
                        throw new IllegalStateException(
                                "Valeurs autorisées invalides pour le paramètre.", invalidValues
                        );
                    }
                }
                return parameter;
            };

    private final JdbcTemplate jdbcTemplate;

    public ToolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ToolDefinition> findAll() {
        List<ToolDefinition> tools = jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled, assistant_enabled, sensitive, result_entity_type, result_collection_key "
                        + "FROM bandy_tool ORDER BY name",
                TOOL_ROW_MAPPER
        );
        return loadParameters(tools);
    }

    public List<ToolDefinition> findEnabled() {
        List<ToolDefinition> tools = jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled, assistant_enabled, sensitive, result_entity_type, result_collection_key "
                        + "FROM bandy_tool WHERE enabled = TRUE ORDER BY name",
                TOOL_ROW_MAPPER
        );
        return loadParameters(tools);
    }

    public Optional<ToolDefinition> findByName(String name) {
        List<ToolDefinition> tools = jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled, assistant_enabled, sensitive, result_entity_type, result_collection_key "
                        + "FROM bandy_tool WHERE name = ? AND enabled = TRUE",
                TOOL_ROW_MAPPER,
                name
        );
        return tools.stream().findFirst().map(this::loadParameters);
    }

    @Transactional
    public ToolDefinition save(ToolDefinition tool) {
        int updatedRows = updateTool(tool);
        if (updatedRows == 0) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO bandy_tool (
                            name, description, when_to_use, http_method, endpoint_url,
                            enabled, assistant_enabled, sensitive, result_entity_type, result_collection_key, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        tool.getName(),
                        tool.getDescription(),
                        tool.getWhenToUse(),
                        tool.getMethod().toUpperCase(),
                        tool.getUrl(),
                        tool.isEnabled(),
                        tool.isAssistantEnabled(),
                        tool.isSensitive(),
                        tool.getResultEntityType(),
                        tool.getResultCollectionKey()
                );
            } catch (DuplicateKeyException concurrentInsert) {
                updateTool(tool);
            }
        }

        jdbcTemplate.update(
                "DELETE FROM bandy_tool_parameter WHERE tool_name = ?",
                tool.getName()
        );
        List<ToolParameter> parameters = tool.getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            ToolParameter parameter = parameters.get(index);
            jdbcTemplate.update("""
                    INSERT INTO bandy_tool_parameter (
                        tool_name, name, type, description, location, required, position, allowed_values, reference_type
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tool.getName(),
                    parameter.getName(),
                    parameter.getType().toLowerCase(),
                    parameter.getDescription(),
                    parameter.getLocation(),
                    parameter.isRequired(),
                    index,
                    serializeAllowedValues(parameter.getAllowedValues()),
                    parameter.getReferenceType()
            );
        }
        return loadParameters(tool);
    }

    private int updateTool(ToolDefinition tool) {
        return jdbcTemplate.update("""
                UPDATE bandy_tool SET
                    description = ?,
                    when_to_use = ?,
                    http_method = ?,
                    endpoint_url = ?,
                    enabled = ?,
                    assistant_enabled = ?,
                    sensitive = ?,
                    result_entity_type = ?,
                    result_collection_key = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE name = ?
                """,
                tool.getDescription(),
                tool.getWhenToUse(),
                tool.getMethod().toUpperCase(),
                tool.getUrl(),
                tool.isEnabled(),
                tool.isAssistantEnabled(),
                tool.isSensitive(),
                tool.getResultEntityType(),
                tool.getResultCollectionKey(),
                tool.getName()
        );
    }

    public boolean delete(String name) {
        return jdbcTemplate.update(
                "DELETE FROM bandy_tool WHERE name = ?",
                name
        ) > 0;
    }

    private String serializeAllowedValues(List<String> allowedValues) {
        if (allowedValues == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(allowedValues);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser les valeurs autorisées.", exception
            );
        }
    }

    private ToolDefinition loadParameters(ToolDefinition tool) {
        tool.setParameters(jdbcTemplate.query(
                "SELECT name, type, description, location, required, allowed_values, reference_type "
                        + "FROM bandy_tool_parameter WHERE tool_name = ? "
                        + "ORDER BY position",
                PARAMETER_ROW_MAPPER,
                tool.getName()
        ));
        return tool;
    }

    private List<ToolDefinition> loadParameters(List<ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return tools;
        }

        String placeholders = String.join(
                ", ",
                java.util.Collections.nCopies(tools.size(), "?")
        );
        Object[] toolNames = tools.stream()
                .map(ToolDefinition::getName)
                .toArray();
        Map<String, List<ToolParameter>> parametersByTool = new HashMap<>();
        tools.forEach(tool -> parametersByTool.put(
                tool.getName(),
                new ArrayList<>()
        ));
        jdbcTemplate.query(
                "SELECT tool_name, name, type, description, location, required, allowed_values, reference_type "
                        + "FROM bandy_tool_parameter WHERE tool_name IN ("
                        + placeholders + ") ORDER BY tool_name, position",
                resultSet -> {
                    ToolParameter parameter = PARAMETER_ROW_MAPPER.mapRow(
                            resultSet,
                            0
                    );
                    parametersByTool.get(resultSet.getString("tool_name"))
                            .add(parameter);
                },
                toolNames
        );
        tools.forEach(tool -> tool.setParameters(
                parametersByTool.get(tool.getName())
        ));
        return tools;
    }
}
