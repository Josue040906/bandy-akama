package com.bandyakama.tool;

import org.springframework.http.HttpStatusCode;

import java.util.Optional;

/**
 * Domain extension point for translating known business HTTP statuses into
 * structured tool results. The orchestration layer knows no domain-specific
 * endpoint or payload convention.
 */
public interface ToolErrorHandler {
    Optional<Object> handle(ToolDefinition tool, HttpStatusCode status);
}
