package com.bandyakama.chat;

import java.util.List;

public record ChatResponse(String message, List<ToolResult> toolResults) {
    public record ToolResult(String name, Object result) {
    }
}
