package com.bandyakama.tool;


import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class ToolRegistry {
    private final ToolRepository toolRepository;
    private final ToolDestinationPolicy destinationPolicy;

    public ToolRegistry(
            ToolRepository toolRepository,
            ToolDestinationPolicy destinationPolicy
    ) {
        this.toolRepository = toolRepository;
        this.destinationPolicy = destinationPolicy;
    }

    public ToolDefinition register(ToolDefinition tool) {
        tool.validate();
        if (!destinationPolicy.allows(tool.getUrl())) {
            throw new IllegalArgumentException(
                    "L’URL de l’outil ne pointe pas vers une origine autorisée "
                            + "(bandy.tools.allowed-origins)."
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
