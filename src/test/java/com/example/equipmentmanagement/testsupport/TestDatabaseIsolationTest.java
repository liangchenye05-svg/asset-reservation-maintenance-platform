package com.example.equipmentmanagement.testsupport;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseIsolationTest {
    @Test void missingConfigurationNeverFallsBackToDailyEnvironment() {
        assertThrows(IllegalStateException.class, () -> TestDatabase.validateSettings(Map.of(), "unit"));
        assertThrows(IllegalStateException.class, () -> TestDatabase.validateSettings(Map.of(
                "DB_URL","jdbc:mysql://127.0.0.1:1/unused", "DB_USERNAME","unused", "DB_PASSWORD","unused"), "unit"));
    }
    @Test void onlyExplicitLocalServerWithoutSchemaOrOptionsIsAccepted() {
        var values=new HashMap<>(Map.of("TEST_DB_BASE","jdbc:mysql://127.0.0.1:3306/",
                "TEST_DB_USERNAME","synthetic", "TEST_DB_PASSWORD","synthetic-not-used"));
        assertDoesNotThrow(() -> TestDatabase.validateSettings(values,"unit"));
        for(String base:List.of("jdbc:mysql://127.0.0.1/", "jdbc:mysql://remote.invalid:3306/",
                "jdbc:mysql://127.0.0.1:0/", "jdbc:mysql://127.0.0.1:65536/",
                "jdbc:mysql://127.0.0.1:3306/?database=mysql", "jdbc:mysql://127.0.0.1:3306/mysql",
                "jdbc:mysql://127.0.0.1:3306/sys", "jdbc:mysql://127.0.0.1:3306/information_schema",
                "jdbc:mysql://127.0.0.1:3306/performance_schema", "jdbc:mysql://127.0.0.1:3306/any_existing_schema")) {
            values.put("TEST_DB_BASE",base);
            assertThrows(IllegalStateException.class, () -> TestDatabase.validateSettings(values,"unit"));
        }
    }
    @Test void missingTestCredentialsAreRejectedBeforeJdbc() {
        var values=new HashMap<>(Map.of("TEST_DB_BASE","jdbc:mysql://localhost:3306/",
                "TEST_DB_USERNAME","synthetic", "TEST_DB_PASSWORD","synthetic"));
        for(String key:List.of("TEST_DB_USERNAME","TEST_DB_PASSWORD")) {
            var incomplete=new HashMap<>(values);incomplete.put(key," ");
            assertThrows(IllegalStateException.class, () -> TestDatabase.validateSettings(incomplete,"unit"));
        }
    }
    @Test void arbitraryAndPrefixLookalikeUrlsFailBeforeDatasourceBeanCreation() {
        // No DriverManager calls: these addresses are never connected, including the system-schema example.
        for(String url:List.of("jdbc:mysql://127.0.0.1:1/mysql",
                "jdbc:mysql://127.0.0.1:1/equipment_it_fake_"+"a".repeat(32))) {
            var properties=disabledProperties();properties.put("spring.datasource.url",url);
            assertRejectedBeforeBeanCreation(properties);
        }
    }
    @Test void migrationAndAlternateDatasourceOverridesFailBeforeBeans() {
        for(String key:List.of("spring.flyway.enabled","spring.flyway.url","spring.flyway.schemas",
                "spring.datasource.hikari.jdbc-url","spring.datasource.jndi-name", "spring.datasource.hikari.catalog",
                "spring.datasource.hikari.connection-init-sql")) {
            var properties=disabledProperties();properties.put(key,key.endsWith("enabled")?"true":"synthetic-invalid-value");
            assertRejectedBeforeBeanCreation(properties);
        }
    }
    @Test void alteredDefaultDriverOrCredentialsFailClosed() {
        for(String key:List.of("spring.datasource.driver-class-name","spring.datasource.username","spring.datasource.password")) {
            var properties=disabledProperties();properties.put(key,"synthetic-invalid-value");
            assertRejectedBeforeBeanCreation(properties);
        }
    }
    private Map<String,Object> disabledProperties() {
        return new HashMap<>(Map.of("spring.datasource.url",NoDatabaseDriver.URL,
                "spring.datasource.driver-class-name",NoDatabaseDriver.class.getName(),
                "spring.datasource.username","unused","spring.datasource.password","unused",
                "spring.flyway.enabled","false","spring.sql.init.mode","never"));
    }
    private void assertRejectedBeforeBeanCreation(Map<String,Object> properties) {
        AtomicInteger beanCreations=new AtomicInteger();
        try(var context=new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-unsafe-settings",properties));
            new IsolatedDatabaseTest.Initializer().initialize(context);
            context.registerBean(DataSource.class, () -> { beanCreations.incrementAndGet();return org.mockito.Mockito.mock(DataSource.class); });
            var failure=assertThrows(IllegalStateException.class, context::refresh);
            assertTrue(failure.getMessage().contains("TEST ISOLATION"));
            assertEquals(0,beanCreations.get(),"Guard must run before any datasource bean is created");
        }
    }
}
