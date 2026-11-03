package com.example.equipmentmanagement;

import com.example.equipmentmanagement.testsupport.IsolatedDatabaseTest;
import com.example.equipmentmanagement.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;

@IsolatedDatabaseTest
@SpringBootTest(properties={"logging.level.root=ERROR", "spring.main.banner-mode=off",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
class EquipmentManagementApplicationTests {
    private static final String KEY = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource
    static void testKey(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> KEY);
    }

    @Test
    void contextLoads() {
        org.junit.jupiter.api.Assertions.assertEquals(
                com.example.equipmentmanagement.testsupport.NoDatabaseDriver.URL,
                environment.getProperty("spring.datasource.url"));
        org.junit.jupiter.api.Assertions.assertFalse(environment.getProperty("spring.flyway.enabled", Boolean.class, true));
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(org.flywaydb.core.Flyway.class).isEmpty());
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

    @Test void defaultDatasourceRejectsConnectionsWithoutNetworkAccess() {
        var failure=org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, dataSource::getConnection);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Connection is not available")
                || failure.getMessage().contains("Database access disabled"));
        org.junit.jupiter.api.Assertions.assertEquals(
                com.example.equipmentmanagement.testsupport.NoDatabaseDriver.class.getName(),
                context.getBean(com.zaxxer.hikari.HikariDataSource.class).getDriverClassName());
    }

}
