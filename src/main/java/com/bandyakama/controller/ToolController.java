package com.bandyakama.controller;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRegistry;
import com.bandyakama.security.ToolRegistrationGuard;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
    private final ToolRegistry toolRegistry;
    private final ToolRegistrationGuard registrationGuard;

    public ToolController(
            ToolRegistry toolRegistry,
            ToolRegistrationGuard registrationGuard
    ) {
        this.toolRegistry = toolRegistry;
        this.registrationGuard = registrationGuard;
    }
    @GetMapping
    public List<ToolDefinition> getTools(
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey
    ){
        registrationGuard.requireAuthorized(suppliedKey);
        return toolRegistry.getAll();
    }
    @PostMapping
    public ToolDefinition registerTool(
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey,
            @Valid @RequestBody ToolDefinition tool
    ) {
        registrationGuard.requireAuthorized(suppliedKey);
        return toolRegistry.register(tool);
    }

    @PutMapping("/{toolName}")
    public ToolDefinition updateTool(
            @PathVariable String toolName,
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey,
            @Valid @RequestBody ToolDefinition tool
    ) {
        registrationGuard.requireAuthorized(suppliedKey);
        if (!toolName.equals(tool.getName())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Le nom dans l’URL doit correspondre au nom de l’outil."
            );
        }
        return toolRegistry.register(tool);
    }

    @DeleteMapping("/{toolName}")
    public void deleteTool(
            @PathVariable String toolName,
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey
    ) {
        registrationGuard.requireAuthorized(suppliedKey);
        if (!toolRegistry.delete(toolName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
