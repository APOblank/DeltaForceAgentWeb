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
    private RateLimits rateLimits = new RateLimits();

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

    public RateLimits getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(RateLimits rateLimits) {
        this.rateLimits = rateLimits;
    }

    public static class Security {
        private String jwtSecret;
        private long jwtTtlSeconds = 3600;
        private String jwtIssuer = "delta-force-agent-web";
        private String jwtAudience = "delta-force-agent";
        private String corsAllowedOrigins = "http://127.0.0.1:8080,http://localhost:8080";
        private int bcryptStrength = 12;
        private boolean requireHttps;
        private String bootstrapAdminUsername;
        private String bootstrapAdminPassword;
        private int knowledgeQueryTimeoutSeconds = 3;

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public long getJwtTtlSeconds() { return jwtTtlSeconds; }
        public void setJwtTtlSeconds(long jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
        public String getJwtIssuer() { return jwtIssuer; }
        public void setJwtIssuer(String jwtIssuer) { this.jwtIssuer = jwtIssuer; }
        public String getJwtAudience() { return jwtAudience; }
        public void setJwtAudience(String jwtAudience) { this.jwtAudience = jwtAudience; }
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
        public int getBcryptStrength() { return bcryptStrength; }
        public void setBcryptStrength(int bcryptStrength) { this.bcryptStrength = bcryptStrength; }
        public boolean isRequireHttps() { return requireHttps; }
        public void setRequireHttps(boolean requireHttps) { this.requireHttps = requireHttps; }
        public String getBootstrapAdminUsername() { return bootstrapAdminUsername; }
        public void setBootstrapAdminUsername(String bootstrapAdminUsername) { this.bootstrapAdminUsername = bootstrapAdminUsername; }
        public String getBootstrapAdminPassword() { return bootstrapAdminPassword; }
        public void setBootstrapAdminPassword(String bootstrapAdminPassword) { this.bootstrapAdminPassword = bootstrapAdminPassword; }
        public int getKnowledgeQueryTimeoutSeconds() { return knowledgeQueryTimeoutSeconds; }
        public void setKnowledgeQueryTimeoutSeconds(int knowledgeQueryTimeoutSeconds) { this.knowledgeQueryTimeoutSeconds = knowledgeQueryTimeoutSeconds; }
    }

    public static class RateLimits {
        private int authPerMinute = 10;
        private int chatPerMinute = 20;
        private int knowledgeQueryPerMinute = 30;
        private int generalApiPerMinute = 600;

        public int getAuthPerMinute() { return authPerMinute; }
        public void setAuthPerMinute(int authPerMinute) { this.authPerMinute = authPerMinute; }
        public int getChatPerMinute() { return chatPerMinute; }
        public void setChatPerMinute(int chatPerMinute) { this.chatPerMinute = chatPerMinute; }
        public int getKnowledgeQueryPerMinute() { return knowledgeQueryPerMinute; }
        public void setKnowledgeQueryPerMinute(int knowledgeQueryPerMinute) { this.knowledgeQueryPerMinute = knowledgeQueryPerMinute; }
        public int getGeneralApiPerMinute() { return generalApiPerMinute; }
        public void setGeneralApiPerMinute(int generalApiPerMinute) { this.generalApiPerMinute = generalApiPerMinute; }
    }

    public static class Fastapi {
        private String baseUrl;
        private String apiKey;
        private String model;
        private boolean preloadPersonalStats;

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

        public boolean isPreloadPersonalStats() {
            return preloadPersonalStats;
        }

        public void setPreloadPersonalStats(boolean preloadPersonalStats) {
            this.preloadPersonalStats = preloadPersonalStats;
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
