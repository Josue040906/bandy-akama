package com.bandyakama.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class ToolParameter {

    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
    private String name;

    @NotBlank
    @Pattern(regexp = "(?i)string|integer|number|boolean")
    private String type;

    @NotBlank
    @Size(max = 1000)
    private String description;

    @Pattern(regexp = "(?i)query|path|body")
    private String location;

    private boolean required;

    @Size(max = 100)
    private List<@NotBlank @Size(max = 120) String> allowedValues;

    public ToolParameter() {
    }

    public ToolParameter(
            String name,
            String type,
            String description,
            boolean required
    ) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public List<String> getAllowedValues() {
        return allowedValues;
    }

    public void setAllowedValues(List<String> allowedValues) {
        this.allowedValues = allowedValues == null ? null : new ArrayList<>(allowedValues);
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public void validate() {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Nom de paramètre invalide.");
        }
        if (type == null || !type.matches("(?i)string|integer|number|boolean")) {
            throw new IllegalArgumentException("Type de paramètre non pris en charge : " + type);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Chaque paramètre doit avoir une description.");
        }
        if (location != null
                && !location.matches("(?i)query|path|body")) {
            throw new IllegalArgumentException(
                    "Emplacement de paramètre non pris en charge : " + location
            );
        }
        if (allowedValues != null) {
            if (!"string".equalsIgnoreCase(type)
                    || allowedValues.isEmpty()
                    || allowedValues.stream().anyMatch(value ->
                            value == null || value.isBlank() || value.length() > 120)
                    || allowedValues.stream().distinct().count() != allowedValues.size()) {
                throw new IllegalArgumentException(
                        "Les valeurs autorisées doivent être une liste non vide, unique et de type string : "
                                + name
                );
            }
        }
    }
}
