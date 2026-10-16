package com.example.equipmentmanagement.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    @Test
    void testGenerateAndParseToken() {

        String secret = Encoders.BASE64.encode(
                Jwts.SIG.HS256.key().build().getEncoded()
        );

        JwtUtil jwtUtil = new JwtUtil(secret, 3600000L);

        Integer userId = 1;

        String token = jwtUtil.generateToken(userId);

        Integer parsedId = jwtUtil.parseToken(token);

        assertEquals(userId, parsedId);
    }

    @Test
    void testTamperedToken() {

        String secret = Encoders.BASE64.encode(
                Jwts.SIG.HS256.key().build().getEncoded()
        );

        JwtUtil jwtUtil = new JwtUtil(secret, 3600000L);

        String token = jwtUtil.generateToken(1);

        // 把原始 JWT 的最后一个字符替换掉
        String[] parts = token.split("\\.");

        String signature = parts[2];

        char first = signature.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';

        parts[2] = replacement + signature.substring(1);

        String tamperedToken = String.join(".", parts);

        assertThrows(
                io.jsonwebtoken.security.SignatureException.class,
                () -> jwtUtil.parseToken(tamperedToken)
        );
    }

    @Test
    void testExpiredToken() {

        String secret = Encoders.BASE64.encode(
                Jwts.SIG.HS256.key().build().getEncoded()
        );

        JwtUtil jwtUtil = new JwtUtil(secret, -60000L);

        String token = jwtUtil.generateToken(1);

        assertThrows(
                io.jsonwebtoken.ExpiredJwtException.class,
                () -> jwtUtil.parseToken(token)
        );
    }

}