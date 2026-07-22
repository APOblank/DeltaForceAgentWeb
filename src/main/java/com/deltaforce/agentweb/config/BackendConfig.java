package com.deltaforce.agentweb.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Config;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class BackendConfig {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public BackendConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Bean
    public Driver neo4jDriver(AppProperties properties) {
        AppProperties.Neo4j neo4j = properties.getNeo4j();
        return GraphDatabase.driver(
                neo4j.getUri(),
                AuthTokens.basic(neo4j.getUser(), neo4j.getPassword()),
                Config.builder()
                        .withConnectionTimeout(5, TimeUnit.SECONDS)
                        .withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
                        .build()
        );
    }

    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(120))
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();
    }

    @Bean(name = "chatRestTemplate")
    public RestTemplate chatRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(130))
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();
    }

    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "chat-stream-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100), factory, new ThreadPoolExecutor.AbortPolicy());
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
                        .allowedHeaders("Authorization", "Content-Type", "X-Request-ID")
                        .exposedHeaders("X-Request-ID", "Retry-After")
                        .allowCredentials(false)
                        .maxAge(3600);
            }

            @Override
            public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(jwtAuthenticationInterceptor).addPathPatterns("/api/**");
                registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
            }

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/firearm-images/**", "/attachment-images/**")
                        .addResourceLocations("classpath:/static/firearm-images/", "classpath:/static/attachment-images/")
                        .setCachePeriod(31_536_000);
            }
        };
    }
}
