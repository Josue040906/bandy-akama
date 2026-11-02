package com.bandyakama.tool;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ToolErrorHandlerRegistry {
    private final List<ToolErrorHandler> handlers;

    public ToolErrorHandlerRegistry(List<ToolErrorHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public Optional<Object> handle(
            ToolDefinition tool,
            HttpStatusCode status
    ) {
        return handlers.stream()
                .map(handler -> handler.handle(tool, status))
                .flatMap(Optional::stream)
                .findFirst();
    }

    public static ToolErrorHandlerRegistry empty() {
        return new ToolErrorHandlerRegistry(List.of());
    }
}
