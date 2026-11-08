package com.bandyakama.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ToolParameter {

    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
    private String name;

    @NotBlank
    @Pattern(regexp = "(?i)string|integer|number|boolean")
    private String type;

    @NotBlank
    @Size(max = 2000)
    private String description;

    /**
     * query, path ou body ; « body.donnees » place l'argument dans l'objet
     * donnees du corps ({"donnees":{"motif":…}}), quand l'API attend un objet.
     */
    @Pattern(regexp = LOCATION)
    private String location;

    static final String LOCATION = "(?i)query|path|body(\\.[A-Za-z][A-Za-z0-9_]{0,40})?";

    private boolean required;

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

    /** L'argument part dans le corps de la requête (directement ou dans un objet). */
    public boolean inBody() {
        return location != null && location.toLowerCase().matches("body(\\..+)?");
    }

    /** L'objet du corps qui reçoit l'argument (« donnees »), ou null s'il est à la racine. */
    public String bodyObject() {
        return inBody() && location.contains(".") ? location.substring(location.indexOf('.') + 1) : null;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
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
        if (location != null && !location.matches(LOCATION)) {
            throw new IllegalArgumentException(
                    "Emplacement de paramètre non pris en charge : " + location
            );
        }
    }
}
