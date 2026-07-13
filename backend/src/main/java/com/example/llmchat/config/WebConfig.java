package com.example.llmchat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public WebConfig(@Value("${app.cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if ("*".equals(allowedOrigins)) {
            registry.addMapping("/api/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                    .allowedHeaders("Authorization", "Content-Type", "X-Local-Llm-Api-Key", "*")
                    .exposedHeaders("Authorization", "Retry-After");
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Local-Llm-Api-Key", "*")
                .exposedHeaders("Authorization", "Retry-After");
    }
}
