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

    private boolean assistantEnabled;

    private boolean sensitive;

    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,39}")
    private String resultEntityType;

    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
    private String resultCollectionKey;

    @NotBlank
    @Size(max = 2000)
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

    public boolean isAssistantEnabled() { return assistantEnabled; }
    public void setAssistantEnabled(boolean assistantEnabled) { this.assistantEnabled = assistantEnabled; }

    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }

    public String getResultEntityType() { return resultEntityType; }
    public void setResultEntityType(String resultEntityType) { this.resultEntityType = resultEntityType; }

    public String getResultCollectionKey() { return resultCollectionKey; }
    public void setResultCollectionKey(String resultCollectionKey) { this.resultCollectionKey = resultCollectionKey; }

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
        if ((resultEntityType == null) != (resultCollectionKey == null)) {
            throw new IllegalArgumentException(
                    "Le type d’entité et la collection de résultat doivent être déclarés ensemble."
            );
        }
        if (resultEntityType != null
                && !resultEntityType.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) {
            throw new IllegalArgumentException("Type d’entité de résultat invalide.");
        }
        if (resultCollectionKey != null
                && !resultCollectionKey.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Collection de résultat invalide.");
        }
        if (parameters == null) {
            parameters = List.of();
        }
        if (parameters.stream().map(ToolParameter::getName).distinct().count()
                != parameters.size()) {
            throw new IllegalArgumentException("Les noms des paramètres doivent être uniques.");
        }
        try {
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
            if (location != null && location.equalsIgnoreCase("body")
                    && method.equalsIgnoreCase("GET")) {
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
