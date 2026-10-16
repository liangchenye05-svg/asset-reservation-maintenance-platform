package com.example.equipmentmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;

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
    }

}
