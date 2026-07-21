package com.deltaforce.agentweb.config;

import org.neo4j.driver.Driver;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("neo4j")
public class Neo4jHealthIndicator implements HealthIndicator {
    private final Driver driver;

    public Neo4jHealthIndicator(Driver driver) {
        this.driver = driver;
    }

    @Override
    public Health health() {
        try {
            driver.verifyConnectivity();
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down().build();
        }
    }
}
