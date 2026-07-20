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
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.time.Duration;

@Configuration
public class BackendConfig {

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
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                String attachmentImages = Paths.get("orzice_attachments_images")
                        .toAbsolutePath()
                        .normalize()
                        .toUri()
                        .toString();
                String firearmImages = Paths.get("orzice_firearms_images")
                        .toAbsolutePath()
                        .normalize()
                        .toUri()
                        .toString();
                registry.addResourceHandler("/attachment-images/**")
                        .addResourceLocations(attachmentImages);
                registry.addResourceHandler("/firearm-images/**")
                        .addResourceLocations(firearmImages);
            }
        };
    }
}
