package com.bandyakama.config;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.bandyakama.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ToolConfiguration {

    @Bean
    public ToolRegistry toolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition searchEmployees = new ToolDefinition(
                "searchEmployees",
                "Recherche des employés dans l'application.",
                "GET",
                "http://localhost:8080/api/employes/search",
                List.of(
                        new ToolParameter(
                                "query",
                                "string",
                                "Texte utilisé pour effectuer la recherche.",
                                true
                        )
                )
        );
        registry.register(searchEmployees);
        ToolDefinition getEmployeeProfile =
                new ToolDefinition(
                        "getEmployeeProfile",
                        "Récupère le profil détaillé d'un employé.",
                        "GET",
                        "http://localhost:8080/api/employes/profile",
                        List.of(
                                new ToolParameter(
                                        "query",
                                        "string",
                                        "Nom, prénom ou matricule de l'employé.",
                                        true
                                )
                        )
                );

        registry.register(getEmployeeProfile);


        ToolDefinition searchServices =
                new ToolDefinition(
                        "searchServices",
                        "Recherche des services dans l'application.",
                        "GET",
                        "http://localhost:8080/api/services/search",
                        List.of(
                                new ToolParameter(
                                        "query",
                                        "string",
                                        "Texte utilisé pour rechercher un service.",
                                        true
                                )
                        )
                );

        registry.register(searchServices);
        return registry;
    }
}
