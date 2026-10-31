package com.bandyakama.assistant;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.bandyakama.tool.ToolExample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class AssistantConfigurationRepository {\n    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONFIGURATION_QUERY = """
            SELECT p.name AS profile_name,
                   p.role AS profile_role,
                   p.instructions AS profile_instructions,
                   t.name AS tool_name,
                   t.description AS tool_description,
                   t.when_to_use AS tool_when_to_use,
                   t.http_method AS tool_http_method,
                   t.endpoint_url AS tool_endpoint_url,
                   t.enabled AS tool_enabled,
                   t.approved_for_gemini AS tool_approved_for_gemini,
                   t.sensitive AS tool_sensitive,
                   parameter.name AS parameter_name,
                   parameter.type AS parameter_type,
                   parameter.description AS parameter_description,
                   parameter.location AS parameter_location,
                   parameter.required AS parameter_required,
                   example.example_text AS example_text,\n                   example.pattern_text AS example_pattern,\n                   example.expected_arguments AS example_expected_arguments
            FROM bandy_assistant_profile p
            LEFT JOIN bandy_tool t
                   ON t.enabled = TRUE AND t.approved_for_gemini = TRUE
            LEFT JOIN bandy_tool_parameter parameter
                   ON parameter.tool_name = t.name
            LEFT JOIN bandy_tool_example example
                   ON example.tool_name = t.name AND example.enabled = TRUE
            WHERE p.id = 1
            ORDER BY t.name, parameter.position, example.position
            """;

    private final JdbcTemplate jdbcTemplate;

    public AssistantConfigurationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AssistantConfigurationSnapshot get() {
        return jdbcTemplate.query(CONFIGURATION_QUERY, resultSet -> {
            if (!resultSet.next()) {
                throw new IllegalStateException(
                        "Le profil de l’assistant n’existe pas."
                );
            }

            AssistantProfile profile = new AssistantProfile(
                    resultSet.getString("profile_name"),
                    resultSet.getString("profile_role"),
                    resultSet.getString("profile_instructions")
            );
            Map<String, ToolDefinition> tools = new LinkedHashMap<>();
            do {
                String toolName = resultSet.getString("tool_name");
                if (toolName == null) {
                    continue;
                }
                ToolDefinition tool = tools.computeIfAbsent(toolName, name -> {
                    ToolDefinition definition = new ToolDefinition(
                            name,
                            resultSetValue(resultSet, "tool_description"),
                            resultSetValue(resultSet, "tool_http_method"),
                            resultSetValue(resultSet, "tool_endpoint_url"),
                            new ArrayList<>()
                    );
                    definition.setWhenToUse(
                            resultSetValue(resultSet, "tool_when_to_use")
                    );
                    try {
                        definition.setEnabled(resultSet.getBoolean("tool_enabled"));
                        definition.setApprovedForGemini(
                                resultSet.getBoolean("tool_approved_for_gemini")
                        );
                        definition.setSensitive(
                                resultSet.getBoolean("tool_sensitive")
                        );
                    } catch (java.sql.SQLException exception) {
                        throw new IllegalStateException(
                                "Impossible de lire la configuration d’un outil.",
                                exception
                        );
                    }
                    return definition;
                });

                String parameterName = resultSet.getString("parameter_name");
                if (parameterName != null
                        && tools.get(toolName).getParameters().stream()
                                .noneMatch(existing -> existing.getName().equals(parameterName))) {
                    ToolParameter parameter = new ToolParameter(
                            parameterName,
                            resultSet.getString("parameter_type"),
                            resultSet.getString("parameter_description"),
                            resultSet.getBoolean("parameter_required")
                    );
                    parameter.setLocation(
                            resultSet.getString("parameter_location")
                    );
                    tool.getParameters().add(parameter);
                }

                String exampleText = resultSet.getString("example_text");
                if (exampleText != null && tool.getExamples().stream()
                        .noneMatch(existing -> existing.getText().equals(exampleText))) {
                    tool.getExamples().add(readExample(
                            exampleText,
                            resultSet.getString("example_pattern"),
                            resultSet.getString("example_expected_arguments")
                    ));
                }
            } while (resultSet.next());

            return new AssistantConfigurationSnapshot(
                    profile,
                    List.copyOf(tools.values())
            );
        });
    }

    private static ToolExample readExample(
            String text,
            String pattern,
            String expectedArgumentsJson
    ) {
        try {
            Map<String, Object> expectedArguments = OBJECT_MAPPER.readValue(
                    expectedArgumentsJson,
                    OBJECT_MAPPER.getTypeFactory().constructMapType(
                            HashMap.class, String.class, Object.class
                    )
            );
            return new ToolExample(text, pattern, expectedArguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Les arguments attendus d’un exemple sont illisibles.",
                    exception
            );
        }
    }

    private static String resultSetValue(
            java.sql.ResultSet resultSet,
            String column
    ) {
        try {
            return resultSet.getString(column);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Impossible de lire la configuration d’un outil.",
                    exception
            );
        }
    }
}
