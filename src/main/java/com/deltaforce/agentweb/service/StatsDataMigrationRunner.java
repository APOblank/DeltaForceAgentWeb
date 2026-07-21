package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatsDataMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StatsDataMigrationRunner.class);
    private static final String MARKER = "relational-stats-v1";

    private final AppProperties properties;
    private final JdbcTemplate jdbc;
    private final Neo4jDataCenterService neo4j;

    public StatsDataMigrationRunner(AppProperties properties, JdbcTemplate jdbc, Neo4jDataCenterService neo4j) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.neo4j = neo4j;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getNeo4j().isStatsMigrationEnabled()) return;
        try {
            if (neo4j.migrationCompleted(MARKER)) return;
            List<String> users = jdbc.query(
                    "SELECT DISTINCT owner_user_id FROM df_game_account WHERE owner_user_id IS NOT NULL AND owner_user_id <> ''",
                    (rs, rowNum) -> rs.getString(1));
            for (String userId : users) neo4j.migrateUserFromLegacyStore(userId);
            neo4j.markMigrationCompleted(MARKER);
            log.info("个人战绩关系库迁移完成: {} 个用户", users.size());
        } catch (Exception ex) {
            // Idempotent import lets the next startup safely retry.
            log.warn("个人战绩迁移暂未完成，将在下次启动重试: {}", ex.getMessage());
        }
    }
}
