package com.bandyakama.tool;
import java.util.List;

public class ToolDefinition {

    private String name;
    private String description;
    private String method;
    private String url;
    private List<ToolParameter> parameters;

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

    public String getMethod() {return method;}
    public void setMethod(String method) {this.method = method;}

    public String getUrl() {return url;}
    public void setUrl(String url) {this.url = url;}

    public List<ToolParameter> getParameters() { return parameters;}
    public void setParameters(List<ToolParameter> parameters) {this.parameters = parameters;}
}
