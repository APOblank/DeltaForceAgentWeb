package com.deltaforce.agentweb.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class BackendConfig {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public BackendConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }

    @Bean
    public Driver neo4jDriver(AppProperties properties) {
        AppProperties.Neo4j neo4j = properties.getNeo4j();
        return GraphDatabase.driver(
                neo4j.getUri(),
                AuthTokens.basic(neo4j.getUser(), neo4j.getPassword())
        );
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(120))
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(AppProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String configured = properties.getSecurity().getCorsAllowedOrigins();
                String[] origins = configured == null ? new String[0] : java.util.Arrays.stream(configured.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
                registry.addMapping("/api/**")
                        .allowedOrigins(origins.length == 0
                                ? new String[]{"http://127.0.0.1:8080", "http://localhost:8080"}
                                : origins)
                        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }

            @Override
            public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(jwtAuthenticationInterceptor).addPathPatterns("/api/**");
            }
        };
    }
}
