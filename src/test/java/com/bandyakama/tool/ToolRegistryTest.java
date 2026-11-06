package com.bandyakama.tool;

import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.assistant.AssistantProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ToolRegistryTest {
    @Autowired
    private ToolRegistry registry;

    @Autowired
    private AssistantProfileRepository profileRepository;

    @Autowired
    private MockMvc mockMvc;

    @Value("${bandy.tools.registration-key}")
    private String registrationKey;

    @Test
    void storesToolDefinitionsAndTheirParametersInTheDatabase() {
        ToolDefinition tool = tool("agentSearch", "GET");
        registry.register(tool);

        ToolDefinition loaded = registry.findByName("agentSearch").orElseThrow();
        assertEquals("GET", loaded.getMethod());
        assertEquals("query", loaded.getParameters().get(0).getName());
        assertTrue(loaded.isEnabled());
        assertFalse(loaded.isSensitive());
    }

    @Test
    void persistsSensitiveToolConfiguration() {
        ToolDefinition tool = tool("selfProfile", "GET");
        tool.setSensitive(true);
        registry.register(tool);

        assertTrue(registry.findByName("selfProfile")
                .orElseThrow()
                .isSensitive());
    }

    @Test
    void persistsTheActionLevelAndDefaultsToConsultation() {
        registry.register(tool("readTool", "GET"));
        ToolDefinition confirmed = tool("confirmedTool", "POST");
        confirmed.setActionLevel(ToolActionLevel.CONFIRM);
        registry.register(confirmed);

        assertEquals(ToolActionLevel.READ,
                registry.findByName("readTool").orElseThrow().getActionLevel());
        assertEquals(ToolActionLevel.CONFIRM,
                registry.findByName("confirmedTool").orElseThrow().getActionLevel());
    }

    @Test
    void registersNavigationToFrontRoutesOnly() {
        ToolParameter agentId = new ToolParameter("agentId", "integer", "Agent.", true);
        agentId.setLocation("path");
        ToolDefinition navigation = new ToolDefinition(
                "open_record", "Opens a record.", "GET", "/agents/{agentId}", List.of(agentId)
        );
        navigation.setWhenToUse("When the user wants to open a record.");
        navigation.setActionLevel(ToolActionLevel.NAVIGATE);
        registry.register(navigation);
        assertEquals(ToolActionLevel.NAVIGATE,
                registry.findByName("open_record").orElseThrow().getActionLevel());

        for (String route : List.of(
                "https://example.test/agents", "//evil.test/agents", "/agents/../admin", "agents"
        )) {
            ToolDefinition invalid = new ToolDefinition(
                    "bad_route", "Bad.", "GET", route, List.of()
            );
            invalid.setWhenToUse("Never.");
            invalid.setActionLevel(ToolActionLevel.NAVIGATE);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> registry.register(invalid), route
            );
        }
    }

    @Test
    void registersPreparedTasksWhoseBodyParametersAreFormFields() {
        ToolParameter motif = new ToolParameter("motif", "string", "Motif.", false);
        motif.setLocation("body");
        ToolDefinition prepare = new ToolDefinition(
                "prepare_document", "Prepares a document.", "GET", "/documents/nouveau", List.of(motif)
        );
        prepare.setWhenToUse("When the user wants a new document.");
        prepare.setActionLevel(ToolActionLevel.PREPARE);
        registry.register(prepare);
        assertEquals(ToolActionLevel.PREPARE,
                registry.findByName("prepare_document").orElseThrow().getActionLevel());

        // Une navigation n'a pas de champs de formulaire.
        ToolDefinition navigation = new ToolDefinition(
                "navigation_with_body", "Bad.", "GET", "/documents", List.of(motif)
        );
        navigation.setWhenToUse("Never.");
        navigation.setActionLevel(ToolActionLevel.NAVIGATE);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> registry.register(navigation)
        );
    }

    @Test
    void refusesAnUnknownActionLevelThroughTheApi() throws Exception {
        mockMvc.perform(post("/api/tools")
                        .header("X-Bandy-Registration-Key", registrationKey)
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "levelTool",
                                  "description": "A tool with a bad level.",
                                  "whenToUse": "Never.",
                                  "method": "GET",
                                  "url": "https://example.test/api",
                                  "actionLevel": "WRITE",
                                  "parameters": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingAToolReplacesItsStoredDefinition() {
        registry.register(tool("agentSearch", "GET"));
        registry.register(tool("agentSearch", "POST"));

        assertEquals(
                "POST",
                registry.findByName("agentSearch").orElseThrow().getMethod()
        );
        assertEquals(1, registry.getAll().stream()
                .filter(tool -> tool.getName().equals("agentSearch"))
                .count());
    }

    @Test
    void disabledToolsRemainManagedButAreNotExposedForExecution() {
        ToolDefinition tool = tool("disabledSearch", "GET");
        tool.setEnabled(false);
        registry.register(tool);

        assertTrue(registry.getAll().stream()
                .anyMatch(item -> item.getName().equals("disabledSearch")));
        assertFalse(registry.findByName("disabledSearch").isPresent());
        assertFalse(registry.getEnabled().stream()
                .anyMatch(item -> item.getName().equals("disabledSearch")));
    }

    @Test
    void assistantRoleAndInstructionsCanBeUpdatedInTheDatabase() {
        AssistantProfile updated = profileRepository.update(
                new AssistantProfile(
                        "Bandy",
                        "RH assistant",
                        "Answer using the registered functions."
                )
        );

        assertEquals("Bandy", updated.name());
        assertEquals("RH assistant", profileRepository.get().role());
        assertEquals(
                "Answer using the registered functions.",
                profileRepository.get().instructions()
        );
    }

    @Test
    void toolsAndAssistantProfileAreManagedThroughProtectedApi() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/tools")
                        .header("X-Bandy-Registration-Key", registrationKey)
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "apiTool",
                                  "description": "A registered API tool.",
                                  "whenToUse": "Use this for API tool tests.",
                                  "method": "GET",
                                  "url": "https://example.test/api",
                                  "parameters": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("apiTool"));

        mockMvc.perform(get("/api/assistant/profile")
                        .header("X-Bandy-Registration-Key", registrationKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());

        mockMvc.perform(put("/api/assistant/profile")
                        .header("X-Bandy-Registration-Key", registrationKey)
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Bandy API test",
                                  "role": "Test assistant",
                                  "instructions": "Use registered tools."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Test assistant"));
    }

    @Test
    void refusesToRegisterAToolOutsideTheAllowedOrigins() throws Exception {
        mockMvc.perform(post("/api/tools")
                        .header("X-Bandy-Registration-Key", registrationKey)
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "foreignTool",
                                  "description": "A tool pointing elsewhere.",
                                  "whenToUse": "Never.",
                                  "method": "GET",
                                  "url": "https://other.test/api",
                                  "parameters": []
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertTrue(registry.getAll().stream()
                .noneMatch(tool -> tool.getName().equals("foreignTool")));
    }

    @Test
    void chatRequiresAnAuthenticatedRhUserToken() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer invalid")
                        .contentType("application/json")
                        .content("""
                                {"message":"Bonjour"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatAllowsBrowserPreflightFromConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/chat")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ));
    }

    private ToolDefinition tool(String name, String method) {
        ToolParameter query = new ToolParameter(
                "query",
                "string",
                "Text to search for.",
                true
        );
        query.setLocation("query");
        ToolDefinition tool = new ToolDefinition(
                name,
                "Searches for agents.",
                method,
                "https://example.test/api/agents",
                List.of(query)
        );
        tool.setWhenToUse("Use when looking for agents.");
        return tool;
    }
}
