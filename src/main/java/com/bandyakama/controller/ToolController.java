package com.bandyakama.controller;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    @GetMapping
    public List<ToolDefinition> getTools(){
        return toolRegistry.getAll();
    }
}
