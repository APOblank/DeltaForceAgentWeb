package com.deltaforce.agentweb.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;

@Component
public class StatsDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(StatsDatabaseInitializer.class);
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,63}");

    private final Driver driver;
    private final AppProperties properties;
    private volatile boolean ready;

    public StatsDatabaseInitializer(Driver driver, AppProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        try {
            ensureReady();
        } catch (Exception ex) {
            // Keep the rest of the site available when Neo4j is temporarily offline.
            log.warn("个人战绩数据库初始化失败，将在首次访问时重试: {}", ex.getMessage());
        }
    }

    public synchronized void ensureReady() {
        if (ready) return;
        String database = database();
        try (Session system = driver.session(SessionConfig.forDatabase("system"))) {
            system.executeWrite(tx -> {
                tx.run("CREATE DATABASE `" + database + "` IF NOT EXISTS WAIT").consume();
                return null;
            });
        } catch (Exception ex) {
            // Community edition cannot create additional databases. Trying the target
            // database below produces a precise error if it is genuinely unavailable.
            log.info("未自动创建数据库 {}: {}", database, ex.getMessage());
        }

        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT stats_user_id IF NOT EXISTS FOR (u:StatsUser) REQUIRE u.userId IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT stats_account_id IF NOT EXISTS FOR (a:GameAccount) REQUIRE a.id IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT stats_battle_id IF NOT EXISTS FOR (b:Battle) REQUIRE b.id IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT stats_player_id IF NOT EXISTS FOR (p:BattlePlayer) REQUIRE p.id IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT stats_item_id IF NOT EXISTS FOR (i:BattleItem) REQUIRE i.id IS UNIQUE").consume();
                tx.run("CREATE INDEX stats_account_owner IF NOT EXISTS FOR (a:GameAccount) ON (a.ownerUserId)").consume();
                tx.run("CREATE INDEX stats_battle_owner_time IF NOT EXISTS FOR (b:Battle) ON (b.ownerUserId, b.eventTime)").consume();
                tx.run("CREATE INDEX stats_battle_owner_room IF NOT EXISTS FOR (b:Battle) ON (b.ownerUserId, b.roomId)").consume();
                return null;
            });
        }
        ready = true;
        log.info("个人战绩数据库 {} 已就绪", database);
    }

    public String database() {
        String value = properties.getNeo4j().getStatsDatabase();
        value = StringUtils.hasText(value) ? value.trim() : "sjzuserdata";
        if (!DATABASE_NAME.matcher(value).matches()) {
            throw new IllegalStateException("非法的 Neo4j 个人战绩数据库名");
        }
        return value;
    }
}
