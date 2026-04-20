package com.rtb.config;

/** PostgreSQL connection config. Not on the hot path — campaigns are loaded at startup and cached. */
public record PostgresConfig(String host, int port, String database, String user, String password) {

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public static PostgresConfig from(AppConfig config) {
        return new PostgresConfig(
                config.get("postgres.host", "localhost"),
                config.getInt("postgres.port", 5432),
                config.get("postgres.database", "rtb"),
                config.get("postgres.user", "rtb"),
                config.get("postgres.password", "rtb")
        );
    }
}
