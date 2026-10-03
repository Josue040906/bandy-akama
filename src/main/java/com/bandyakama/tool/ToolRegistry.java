package com.bandyakama.tool;


import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ToolRegistry {
    private final List<ToolDefinition> tools = new ArrayList<>();


    public void register(ToolDefinition tool) { tools.add(tool); }

    public List<ToolDefinition> getAll() {  return List.copyOf(tools); }


    public Optional<ToolDefinition> findByName(String name) {
        return tools.stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst();
    }
}
