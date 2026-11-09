package com.bandyakama.tool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ToolDefinition {

    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
    private String name;

    @NotBlank
    @Size(max = 2000)
    private String description;

    @NotBlank
    @Pattern(regexp = "(?i)GET|POST|PUT|PATCH|DELETE")
    private String method;

    @NotBlank
    @Size(max = 2048)
    private String url;

    @Valid
    private List<ToolParameter> parameters;

    private boolean enabled = true;

    private boolean sensitive;

    private ToolActionLevel actionLevel = ToolActionLevel.READ;

    /**
     * Champs gardés comme contexte de l'utilisateur connecté (par exemple son
     * prénom). Vide pour un outil ordinaire. Bandy appelle l'outil une fois
     * par conversation, sans argument, et ne conserve que ces champs.
     */
    private List<String> userContextFields = List.of();

    /**
     * Tâche confirmée seulement : paramètre d'adresse (« apercu=true ») qui
     * demande à l'API de vérifier la tâche sans l'exécuter. Bandy l'appelle
     * avant d'afficher la carte : un refus est expliqué tout de suite, et la
     * carte montre le résumé de l'API (champ resume) plutôt que des identifiants.
     */
    private String previewQuery;

    @NotBlank
    @Size(max = 4000)
    private String whenToUse;

    public ToolDefinition() {
    }

    public ToolDefinition(
            String name,
            String description,
            String method,
            String url,
            List<ToolParameter> parameters
    ) {
        this.name = name;
        this.description = description;
        this.method = method;
        this.url = url;
        this.parameters = parameters;
    }

    public String getName() {return name;}
    public void setName(String name) {this.name = name;}

    public String getDescription() { return description;}
    public void setDescription(String description) {this.description = description;}

    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }

    public String getMethod() {return method;}
    public void setMethod(String method) {this.method = method;}

    public String getUrl() {return url;}
    public void setUrl(String url) {this.url = url;}

    public List<ToolParameter> getParameters() { return parameters;}
    public void setParameters(List<ToolParameter> parameters) {this.parameters = parameters;}

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }

    /** Navigation et tâche préparée visent une route du front, jamais une API. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean targetsInterface() {
        return actionLevel == ToolActionLevel.NAVIGATE
                || actionLevel == ToolActionLevel.PREPARE;
    }

    public String getPreviewQuery() { return previewQuery; }
    public void setPreviewQuery(String previewQuery) {
        this.previewQuery = previewQuery == null || previewQuery.isBlank() ? null : previewQuery.trim();
    }

    public List<String> getUserContextFields() { return userContextFields; }
    public void setUserContextFields(List<String> userContextFields) {
        this.userContextFields = userContextFields == null ? List.of() : List.copyOf(userContextFields);
    }

    public ToolActionLevel getActionLevel() { return actionLevel; }
    public void setActionLevel(ToolActionLevel actionLevel) {
        this.actionLevel = actionLevel == null ? ToolActionLevel.READ : actionLevel;
    }

    public void validate() {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Nom d’outil invalide.");
        }
        if (description == null || description.isBlank()
                || whenToUse == null || whenToUse.isBlank()) {
            throw new IllegalArgumentException(
                    "L’outil doit décrire sa fonction et quand l’utiliser."
            );
        }
        if (method == null
                || !method.matches("(?i)GET|POST|PUT|PATCH|DELETE")) {
            throw new IllegalArgumentException("Méthode HTTP non prise en charge.");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("L’URL de l’outil est obligatoire.");
        }
        if (parameters == null) {
            parameters = List.of();
        }
        if (parameters.stream().map(ToolParameter::getName).distinct().count()
                != parameters.size()) {
            throw new IllegalArgumentException("Les noms des paramètres doivent être uniques.");
        }
        if (!userContextFields.isEmpty()) {
            // Lu sans argument, une fois par conversation : seulement une consultation.
            if (actionLevel != ToolActionLevel.READ
                    || parameters.stream().anyMatch(ToolParameter::isRequired)) {
                throw new IllegalArgumentException(
                        "Un outil de contexte utilisateur doit être une consultation sans paramètre obligatoire."
                );
            }
            if (userContextFields.size() > 5 || userContextFields.stream()
                    .anyMatch(field -> field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
                throw new IllegalArgumentException(
                        "userContextFields : au plus 5 noms de champs simples."
                );
            }
        }
        if (previewQuery != null && (actionLevel != ToolActionLevel.CONFIRM
                || !previewQuery.matches("[A-Za-z][A-Za-z0-9_]{0,40}=[A-Za-z0-9_]{1,40}"))) {
            throw new IllegalArgumentException(
                    "previewQuery est réservé à une tâche confirmée et s'écrit nom=valeur (par exemple apercu=true)."
            );
        }
        if (targetsInterface()) {
            // Navigation et tâche préparée ouvrent une page : méthode GET,
            // route relative de l'interface, jamais une API.
            if (!method.equalsIgnoreCase("GET")) {
                throw new IllegalArgumentException(
                        "Une navigation ou une tâche préparée utilise la méthode GET."
                );
            }
            if (!url.matches("/(?!/)[A-Za-z0-9._~{}/-]*") || url.contains("..")) {
                throw new IllegalArgumentException(
                        "Une navigation ou une tâche préparée doit viser une route de "
                                + "l’interface commençant par /, par exemple /agents/{agentId}."
                );
            }
        } else try {
            String checkedUrl = url.replaceAll(
                    "\\{[A-Za-z][A-Za-z0-9_]{0,63}}",
                    "path-value"
            );
            java.net.URI parsedUrl = java.net.URI.create(checkedUrl);
            if (!("http".equalsIgnoreCase(parsedUrl.getScheme())
                    || "https".equalsIgnoreCase(parsedUrl.getScheme()))
                    || parsedUrl.getHost() == null
                    || parsedUrl.getUserInfo() != null
                    || parsedUrl.getFragment() != null) {
                throw new IllegalArgumentException("L’URL de l’outil doit être une URL HTTP(S) valide.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("L’URL de l’outil doit être une URL HTTP(S) valide.", exception);
        }
        parameters.forEach(parameter -> {
            parameter.validate();
            String location = parameter.getLocation();
            // Pour une tâche préparée, les paramètres body sont les champs
            // du formulaire ; ailleurs, une fonction GET n'a pas de corps.
            if (parameter.inBody()
                    && method.equalsIgnoreCase("GET")
                    && actionLevel != ToolActionLevel.PREPARE) {
                throw new IllegalArgumentException(
                        "Une fonction GET ne peut pas déclarer un paramètre body."
                );
            }
            if (location != null && location.equalsIgnoreCase("path")
                    && !url.contains("{" + parameter.getName() + "}")) {
                throw new IllegalArgumentException(
                        "Le paramètre path doit être présent dans l’URL : "
                                + parameter.getName()
                );
            }
        });
        java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("\\{([A-Za-z][A-Za-z0-9_]{0,63})}")
                .matcher(url);
        while (pathMatcher.find()) {
            String pathParameter = pathMatcher.group(1);
            boolean declared = parameters.stream().anyMatch(parameter ->
                    parameter.getName().equals(pathParameter)
                            && parameter.isRequired()
                            && "path".equalsIgnoreCase(parameter.getLocation())
            );
            if (!declared) {
                throw new IllegalArgumentException(
                        "Chaque variable de chemin doit être un paramètre path obligatoire : "
                                + pathParameter
                );
            }
        }
    }
}
