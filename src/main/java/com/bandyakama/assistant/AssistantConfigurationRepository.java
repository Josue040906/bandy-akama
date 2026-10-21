package com.bandyakama.assistant;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AssistantConfigurationRepository {
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
                   t.sensitive AS tool_sensitive,
                   parameter.name AS parameter_name,
                   parameter.type AS parameter_type,
                   parameter.description AS parameter_description,
                   parameter.location AS parameter_location,
                   parameter.required AS parameter_required
            FROM bandy_assistant_profile p
            LEFT JOIN bandy_tool t ON t.enabled = TRUE
            LEFT JOIN bandy_tool_parameter parameter
                   ON parameter.tool_name = t.name
            WHERE p.id = 1
            ORDER BY t.name, parameter.position
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
                if (parameterName != null) {
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
            } while (resultSet.next());

            return new AssistantConfigurationSnapshot(
                    profile,
                    List.copyOf(tools.values())
            );
        });
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
