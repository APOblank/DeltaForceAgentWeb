package com.deltaforce.agentweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "delta-force")
public class AppProperties {

    private Fastapi fastapi = new Fastapi();
    private RealtimeData realtimeData = new RealtimeData();
    private Neo4j neo4j = new Neo4j();
    private Data data = new Data();
    private Security security = new Security();

    public Fastapi getFastapi() {
        return fastapi;
    }

    public void setFastapi(Fastapi fastapi) {
        this.fastapi = fastapi;
    }

    public RealtimeData getRealtimeData() {
        return realtimeData;
    }

    public void setRealtimeData(RealtimeData realtimeData) {
        this.realtimeData = realtimeData;
    }

    public Neo4j getNeo4j() {
        return neo4j;
    }

    public void setNeo4j(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public static class Security {
        private String jwtSecret;
        private long jwtTtlSeconds = 86400;
        private String corsAllowedOrigins = "http://127.0.0.1:8080,http://localhost:8080";

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public long getJwtTtlSeconds() { return jwtTtlSeconds; }
        public void setJwtTtlSeconds(long jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    }

    public static class Fastapi {
        private String baseUrl;
        private String apiKey;
        private String model;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class RealtimeData {
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Neo4j {
        private String uri;
        private String user;
        private String password;
        private String database;
        private String userDatabase;
        private String statsDatabase = "sjzuserdata";
        private boolean statsMigrationEnabled = true;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUserDatabase() {
            return userDatabase;
        }

        public void setUserDatabase(String userDatabase) {
            this.userDatabase = userDatabase;
        }

        public String getStatsDatabase() { return statsDatabase; }
        public void setStatsDatabase(String statsDatabase) { this.statsDatabase = statsDatabase; }
        public boolean isStatsMigrationEnabled() { return statsMigrationEnabled; }
        public void setStatsMigrationEnabled(boolean statsMigrationEnabled) { this.statsMigrationEnabled = statsMigrationEnabled; }
    }

    public static class Data {
        private String firearms;
        private String attachments;

        public String getFirearms() {
            return firearms;
        }

        public void setFirearms(String firearms) {
            this.firearms = firearms;
        }

        public String getAttachments() {
            return attachments;
        }

        public void setAttachments(String attachments) {
            this.attachments = attachments;
        }
    }
}
