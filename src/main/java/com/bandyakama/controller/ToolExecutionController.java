package com.bandyakama.controller;

import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.security.ToolRegistrationGuard;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRegistry;
import com.bandyakama.tool.ToolPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class ToolExecutionController {
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ToolRegistrationGuard registrationGuard;

    public ToolExecutionController(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolRegistrationGuard registrationGuard
    ){
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.registrationGuard = registrationGuard;
    }

    @PostMapping("/{toolName}/execute")
    public ResponseEntity<?> executeTool(
            @PathVariable String toolName,
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey,
            @RequestBody Map<String, Object> arguments
    ){
        registrationGuard.requireAuthorized(suppliedKey);
        var toolOptional = toolRegistry.findByName(toolName);
        if(toolOptional.isEmpty()){
            return ResponseEntity.notFound().build();
        }
        ToolDefinition tool =  toolOptional.get();
        try {
            Object result = toolExecutor.execute(tool, arguments);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", exception.getMessage()));
        }
    }
}
