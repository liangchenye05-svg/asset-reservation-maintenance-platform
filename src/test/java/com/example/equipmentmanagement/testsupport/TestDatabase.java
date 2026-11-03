package com.example.equipmentmanagement.testsupport;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One boundary for explicit test-server configuration. Never reads daily application settings. */
public final class TestDatabase {
    public enum Init { CURRENT, V1, EMPTY }
    private static final String OPTIONS = "?serverTimezone=UTC&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";
    private static final Map<String, TestDatabase> CREATED = new ConcurrentHashMap<>();
    private final String schema, url, username, password;
    private boolean springFlyway;

    private TestDatabase(String base, String username, String password, String suite) {
        this.schema = "equipment_it_" + suite + "_" + UUID.randomUUID().toString().replace("-", "");
        this.url = base + schema + OPTIONS;
        this.username = username;
        this.password = password;
    }

    static void validateSettings(Map<String,String> values, String suite) {
        String base = values.get("TEST_DB_BASE");
        if (base == null || !base.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}/"))
            throw new IllegalStateException("TEST_DB_BASE must explicitly name a local test server and port, with no schema or parameters");
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1, base.length() - 1));
        if (port < 1 || port > 65535) throw new IllegalStateException("Invalid test server port");
        for (String key : List.of("TEST_DB_USERNAME", "TEST_DB_PASSWORD"))
            if (values.get(key) == null || values.get(key).isBlank())
                throw new IllegalStateException("Explicit " + key + " required; daily DB configuration is never a fallback");
        if (!suite.matches("[a-z]{1,12}")) throw new IllegalArgumentException("Invalid test suite name");
    }

    public static TestDatabase create(String suite, Init init) throws Exception {
        Map<String,String> values = System.getenv();
        validateSettings(values, suite);
        String base = values.get("TEST_DB_BASE");
        var database = new TestDatabase(base, values.get("TEST_DB_USERNAME"), values.get("TEST_DB_PASSWORD"), suite);
        // CREATE without IF NOT EXISTS: a pre-existing schema is never reused or altered.
        try (var c = DriverManager.getConnection(base + OPTIONS, database.username, database.password); var s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `" + database.schema + "`");
        }
        if (init != Init.EMPTY) {
            Path file = init == Init.CURRENT ? Path.of("schema.sql") : Path.of("src/main/resources/db/migration/V1__initial_schema.sql");
            String ddl = Files.readString(file).replaceAll("(?m)^\\s*--.*$", "");
            try (var c = database.open(); var s = c.createStatement()) {
                for (String sql : ddl.split(";")) if (!sql.isBlank()) s.execute(sql);
            }
        }
        CREATED.put(database.url, database);
        System.out.println("ISOLATED_TEST_SCHEMA=" + database.schema);
        return database;
    }

    public Connection open() throws SQLException { return DriverManager.getConnection(url, username, password); }
    public String schema() { return schema; }
    public void register(DynamicPropertyRegistry registry, boolean migrate) {
        springFlyway = migrate;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> migrate);
        registry.add("spring.sql.init.mode", () -> "never");
    }
    public Flyway flyway() {
        return Flyway.configure().dataSource(url, username, password).baselineOnMigrate(false).baselineVersion("1").cleanDisabled(true).load();
    }

    public static void verifyEnvironment(Environment env) {
        String url = env.getProperty("spring.datasource.url");
        TestDatabase database = CREATED.get(url == null ? "" : url);
        boolean disabled = NoDatabaseDriver.URL.equals(url);
        if (!disabled && database == null) throw new IllegalStateException("TEST ISOLATION: datasource was not created by this test process");
        String driver = disabled ? NoDatabaseDriver.class.getName() : "com.mysql.cj.jdbc.Driver";
        String username = disabled ? "unused" : database.username;
        String password = disabled ? "unused" : database.password;
        if (!driver.equals(env.getProperty("spring.datasource.driver-class-name"))
                || !username.equals(env.getProperty("spring.datasource.username"))
                || !password.equals(env.getProperty("spring.datasource.password")))
            throw new IllegalStateException("TEST ISOLATION: datasource credentials or driver overridden");
        boolean expectedFlyway = !disabled && database.springFlyway;
        if (env.getProperty("spring.flyway.enabled", Boolean.class, true) != expectedFlyway
                || !"never".equals(env.getProperty("spring.sql.init.mode")))
            throw new IllegalStateException("TEST ISOLATION: unexpected migration or SQL initialization settings");
        // Spring/Hikari/Flyway can otherwise use a second datasource independent of datasource.url.
        for (String key : List.of("spring.datasource.jndi-name", "spring.datasource.type",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-j-n-d-i", "spring.datasource.hikari.username", "spring.datasource.hikari.password",
                "spring.datasource.hikari.catalog", "spring.datasource.hikari.schema", "spring.datasource.hikari.connection-init-sql",
                "spring.flyway.url", "spring.flyway.user", "spring.flyway.password", "spring.flyway.schemas", "spring.flyway.default-schema"))
            if (env.getProperty(key) != null && !env.getProperty(key).isBlank())
                throw new IllegalStateException("TEST ISOLATION: alternate datasource setting forbidden: " + key);
    }
}
