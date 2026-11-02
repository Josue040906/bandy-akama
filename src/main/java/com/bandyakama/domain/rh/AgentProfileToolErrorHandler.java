package com.bandyakama.domain.rh;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolErrorHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * SYGPERS-specific interpretation of profile lookup outcomes. This adapter is
 * deliberately outside the generic Gemini orchestration package.
 */
@Component
public class AgentProfileToolErrorHandler implements ToolErrorHandler {
    @Override
    public Optional<Object> handle(
            ToolDefinition tool,
            HttpStatusCode status
    ) {
        if (tool == null || !"get_agent_profile".equals(tool.getName())) {
            return Optional.empty();
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return Optional.of(Map.of(
                    "found", false,
                    "message", "Aucun agent ne correspond exactement à cette "
                            + "identité. Demandez le nom complet exact ou le matricule."
            ));
        }
        if (status.value() == HttpStatus.CONFLICT.value()) {
            return Optional.of(Map.of(
                    "ambiguous", true,
                    "message", "Plusieurs agents correspondent. Demandez le "
                            + "matricule ou un autre identifiant distinctif."
            ));
        }
        return Optional.empty();
    }
}
