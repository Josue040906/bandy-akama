package com.bandyakama.tool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @Valid
    @Size(max = 20)
    private List<ToolExample> examples = new ArrayList<>();
    private boolean enabled = true;
    private boolean approvedForGemini;
    private boolean sensitive;
    @NotBlank
    @Size(max = 2000)
    private String whenToUse;

    public ToolDefinition() {
    }

    public ToolDefinition(String name, String description, String method,
                          String url, List<ToolParameter> parameters) {
        this.name = name;
        this.description = description;
        this.method = method;
        this.url = url;
        this.parameters = parameters;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<ToolParameter> getParameters() { return parameters; }
    public void setParameters(List<ToolParameter> parameters) { this.parameters = parameters; }
    public List<ToolExample> getExamples() { return examples; }
    public void setExamples(List<ToolExample> examples) {
        this.examples = examples == null ? new ArrayList<>() : new ArrayList<>(examples);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isApprovedForGemini() { return approvedForGemini; }
    public void setApprovedForGemini(boolean approvedForGemini) {
        this.approvedForGemini = approvedForGemini;
    }
    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }

    public void validate() {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Nom d’outil invalide.");
        }
        if (description == null || description.isBlank()
                || whenToUse == null || whenToUse.isBlank()) {
            throw new IllegalArgumentException("L’outil doit décrire sa fonction et quand l’utiliser.");
        }
        if (method == null || !method.matches("(?i)GET|POST|PUT|PATCH|DELETE")) {
            throw new IllegalArgumentException("Méthode HTTP non prise en charge.");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("L’URL de l’outil est obligatoire.");
        }
        if (parameters == null) parameters = List.of();
        if (parameters.size() > 50) {
            throw new IllegalArgumentException("Un outil ne peut pas déclarer plus de 50 paramètres.");
        }
        if (examples == null) examples = new ArrayList<>();
        if (examples.size() > 20) {
            throw new IllegalArgumentException("Un outil ne peut pas déclarer plus de 20 exemples.");
        }
        if (parameters.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("La définition contient un paramètre invalide.");
        }
        if (parameters.stream().map(ToolParameter::getName).distinct().count() != parameters.size()) {
            throw new IllegalArgumentException("Les noms des paramètres doivent être uniques.");
        }
        Set<String> parameterNames = new HashSet<>();
        parameters.forEach(parameter -> {
            parameter.validate();
            parameterNames.add(parameter.getName());
            String location = parameter.getLocation();
            if (location != null && location.equalsIgnoreCase("body")
                    && method.equalsIgnoreCase("GET")) {
                throw new IllegalArgumentException("Une fonction GET ne peut pas déclarer un paramètre body.");
            }
            if (location != null && location.equalsIgnoreCase("path")
                    && !url.contains("{" + parameter.getName() + "}")) {
                throw new IllegalArgumentException("Le paramètre path doit être présent dans l’URL : " + parameter.getName());
            }
        });
        Set<String> texts = new HashSet<>();
        for (ToolExample example : examples) {
            if (example == null) throw new IllegalArgumentException("Un exemple ne peut pas être nul.");
            example.validate();
            if (!texts.add(example.getText().trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Les exemples d’un outil doivent être uniques.");
            }
            if (!parameterNames.containsAll(example.getExpectedArguments().keySet())) {
                throw new IllegalArgumentException("Un exemple référence un paramètre non déclaré pour " + name + ".");
            }
        }
        try {
            String checkedUrl = url.replaceAll("\\{[A-Za-z][A-Za-z0-9_]{0,63}}", "path-value");
            java.net.URI parsedUrl = java.net.URI.create(checkedUrl);
            if (!( "http".equalsIgnoreCase(parsedUrl.getScheme())
                    || "https".equalsIgnoreCase(parsedUrl.getScheme()))
                    || parsedUrl.getHost() == null || parsedUrl.getUserInfo() != null
                    || parsedUrl.getFragment() != null) {
                throw new IllegalArgumentException("L’URL de l’outil doit être une URL HTTP(S) valide.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("L’URL de l’outil doit être une URL HTTP(S) valide.", exception);
        }
        java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("\\{([A-Za-z][A-Za-z0-9_]{0,63})}").matcher(url);
        while (pathMatcher.find()) {
            String pathParameter = pathMatcher.group(1);
            boolean declared = parameters.stream().anyMatch(parameter ->
                    parameter.getName().equals(pathParameter) && parameter.isRequired()
                            && "path".equalsIgnoreCase(parameter.getLocation()));
            if (!declared) {
                throw new IllegalArgumentException("Chaque variable de chemin doit être un paramètre path obligatoire : " + pathParameter);
            }
        }
    }
}
