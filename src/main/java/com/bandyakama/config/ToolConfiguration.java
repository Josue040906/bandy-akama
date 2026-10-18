package com.bandyakama.config;

import com.bandyakama.tool.ToolRegistry;
import com.bandyakama.tool.ToolRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfiguration {

    @Bean
    public ToolRegistry toolRegistry(ToolRepository toolRepository) {
                return new ToolRegistry(toolRepository);
    }
}