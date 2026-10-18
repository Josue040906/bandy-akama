package com.bandyakama.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class ToolRepository {
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
                return parameter;
            };

    private final JdbcTemplate jdbcTemplate;

    public ToolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ToolDefinition> findAll() {
        return jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled "
                        + "FROM bandy_tool ORDER BY name",
                TOOL_ROW_MAPPER
        ).stream().map(this::loadParameters).toList();
    }

    public List<ToolDefinition> findEnabled() {
        return jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled "
                        + "FROM bandy_tool WHERE enabled = TRUE ORDER BY name",
                TOOL_ROW_MAPPER
        ).stream().map(this::loadParameters).toList();
    }

    public Optional<ToolDefinition> findByName(String name) {
        List<ToolDefinition> tools = jdbcTemplate.query(
                "SELECT name, description, when_to_use, http_method, endpoint_url, enabled "
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
                            enabled, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        tool.getName(),
                        tool.getDescription(),
                        tool.getWhenToUse(),
                        tool.getMethod().toUpperCase(),
                        tool.getUrl(),
                        tool.isEnabled()
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
                        tool_name, name, type, description, location, required, position
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    tool.getName(),
                    parameter.getName(),
                    parameter.getType().toLowerCase(),
                    parameter.getDescription(),
                    parameter.getLocation(),
                    parameter.isRequired(),
                    index
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
                    updated_at = CURRENT_TIMESTAMP
                WHERE name = ?
                """,
                tool.getDescription(),
                tool.getWhenToUse(),
                tool.getMethod().toUpperCase(),
                tool.getUrl(),
                tool.isEnabled(),
                tool.getName()
        );
    }

    public boolean delete(String name) {
        return jdbcTemplate.update(
                "DELETE FROM bandy_tool WHERE name = ?",
                name
        ) > 0;
    }

    private ToolDefinition loadParameters(ToolDefinition tool) {
        tool.setParameters(jdbcTemplate.query(
                "SELECT name, type, description, location, required "
                        + "FROM bandy_tool_parameter WHERE tool_name = ? "
                        + "ORDER BY position",
                PARAMETER_ROW_MAPPER,
                tool.getName()
        ));
        return tool;
    }
}
