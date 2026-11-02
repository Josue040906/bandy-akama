package com.bandyakama.tool;


import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class ToolRegistry {
    private final ToolRepository toolRepository;

    public ToolRegistry(ToolRepository toolRepository) {
        this.toolRepository = toolRepository;
    }

    public ToolDefinition register(ToolDefinition tool) {
        tool.validate();
        if (ToolPolicy.isReservedName(tool.getName())) {
            throw new IllegalArgumentException(
                    "Ce nom est réservé au moteur conversationnel."
            );
        }
        return toolRepository.save(tool);
    }

    public List<ToolDefinition> getAll() {
        return toolRepository.findAll().stream()
                .sorted(Comparator.comparing(ToolDefinition::getName))
                .toList();
    }

    public List<ToolDefinition> getEnabled() {
        return toolRepository.findEnabled();
    }

    public Optional<ToolDefinition> findByName(String name) {
        return toolRepository.findByName(name);
    }

    public boolean delete(String name) {
        return toolRepository.delete(name);
    }
}
