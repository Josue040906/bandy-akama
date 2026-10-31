package com.bandyakama.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A natural-language example paired with its parameterized pattern and
 * the arguments expected from the model.
 */
public class ToolExample {
    private String text;
    private String pattern;
    private Map<String, Object> expectedArguments = new LinkedHashMap<>();

    public ToolExample() {
    }

    public ToolExample(String text, String pattern, Map<String, Object> expectedArguments) {
        this.text = text;
        this.pattern = pattern;
        setExpectedArguments(expectedArguments);
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public Map<String, Object> getExpectedArguments() {
        return expectedArguments;
    }

    public void setExpectedArguments(Map<String, Object> expectedArguments) {
        this.expectedArguments = expectedArguments == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(expectedArguments);
    }

    public void validate() {
        if (text == null || text.isBlank() || text.length() > 500) {
            throw new IllegalArgumentException(
                    "Chaque exemple doit contenir entre 1 et 500 caractères."
            );
        }
        if (pattern == null || pattern.isBlank() || pattern.length() > 500) {
            throw new IllegalArgumentException(
                    "Chaque modèle d’exemple doit contenir entre 1 et 500 caractères."
            );
        }
        if (expectedArguments == null || expectedArguments.size() > 50
                || expectedArguments.keySet().stream().anyMatch(
                        key -> key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                )) {
            throw new IllegalArgumentException(
                    "Les paramètres attendus de l’exemple sont invalides."
            );
        }
    }
}
