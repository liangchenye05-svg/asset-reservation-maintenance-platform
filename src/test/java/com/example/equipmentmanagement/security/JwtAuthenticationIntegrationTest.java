package com.example.equipmentmanagement.security;

import com.example.equipmentmanagement.config.SecurityConfig;
import com.example.equipmentmanagement.controller.DeviceController;
import com.example.equipmentmanagement.controller.UserController;
import com.example.equipmentmanagement.entity.User;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.UserMapper;
import com.example.equipmentmanagement.service.DeviceService;
import com.example.equipmentmanagement.service.UserService;
import com.example.equipmentmanagement.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Embedded servlet container is essential: MockMvc does not perform Tomcat error dispatches.
// Only database access is mocked; the JWT, security chain, controller and device service are real.
@SpringBootTest(classes = JwtAuthenticationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=optional:classpath:/jwt-test-empty.properties",
                "logging.level.root=ERROR", "spring.main.banner-mode=off"})
class JwtAuthenticationIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired SecretKey testSigningKey;
    @Autowired UserMapper userMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired SecurityFilterChain securityFilterChain;
    @Autowired ServletContext servletContext;
    @Autowired AtomicInteger errorStatus;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        reset(userMapper, deviceMapper);
        errorStatus.set(0);
        User user = new User();
        user.setId(1);
        user.setRole("USER");
        when(userMapper.selectById(1)).thenReturn(user);
        when(deviceMapper.getDeviceByStatus("AVAILABLE")).thenReturn(List.of());
    }

    private int get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        // Discard bodies so assertions/logs never include credentials or tokens.
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void validJwtReachesProtectedDeviceController() throws Exception {
        assertEquals(200, get("/devices?status=AVAILABLE", jwtUtil.generateToken(1)));
        verify(userMapper, times(1)).selectById(1);
        verify(deviceMapper, times(1)).getDeviceByStatus("AVAILABLE");
    }

    @Test
    void missingStatusPreservesBadRequestAfterAuthentication() throws Exception {
        assertEquals(400, get("/devices", jwtUtil.generateToken(1)));
        assertEquals(400, errorStatus.get());
        verify(userMapper, times(1)).selectById(1);
        verifyNoInteractions(deviceMapper);
    }

    @Test
    void invalidJwtPreservesUnauthorized() throws Exception {
        assertEquals(401, get("/devices?status=AVAILABLE", "invalid-test-token"));
        assertEquals(401, errorStatus.get());
        verifyNoInteractions(userMapper, deviceMapper);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        assertEquals(401, get("/devices?status=AVAILABLE", null));
        verifyNoInteractions(userMapper, deviceMapper);
    }

    @Test
    void expiredJwtIsRejected() throws Exception {
        String expiredToken = Jwts.builder().subject("1")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(testSigningKey).compact();
        assertEquals(401, get("/devices?status=AVAILABLE", expiredToken));
        verifyNoInteractions(userMapper, deviceMapper);
    }

    @Test
    void jwtSignedWithAnotherKeyIsRejected() throws Exception {
        JwtUtil otherIssuer = new JwtUtil(
                Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded()), 60_000);
        assertEquals(401, get("/devices?status=AVAILABLE", otherIssuer.generateToken(1)));
        verifyNoInteractions(userMapper, deviceMapper);
    }

    @Test
    void deletedUserIsRejected() throws Exception {
        assertEquals(401, get("/devices?status=AVAILABLE", jwtUtil.generateToken(2)));
        verify(userMapper).selectById(2);
        verifyNoInteractions(deviceMapper);
    }

    @Test
    void authenticationDoesNotLeakToNextRequest() throws Exception {
        assertEquals(200, get("/devices?status=AVAILABLE", jwtUtil.generateToken(1)));
        assertEquals(401, get("/devices?status=AVAILABLE", null));
        verify(deviceMapper, times(1)).getDeviceByStatus("AVAILABLE");
    }

    @Test
    void directErrorUrlDoesNotBypassAuthentication() throws Exception {
        assertEquals(401, get("/error", null));
    }

    @Test
    void emptyBearerIsRejected() throws Exception {
        assertEquals(401, get("/devices?status=AVAILABLE", ""));
        verifyNoInteractions(userMapper, deviceMapper);
    }

    @Test
    void validJwtPreservesNotFound() throws Exception {
        assertEquals(404, get("/route-that-does-not-exist", jwtUtil.generateToken(1)));
        assertEquals(404, errorStatus.get());
    }

    @Test
    void publicLoginIssuesJwtThatCanAccessDevices() throws Exception {
        String testPassword = UUID.randomUUID().toString();
        User user = new User();
        user.setId(1);
        user.setRole("USER");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(testPassword));
        when(userMapper.selectByUsername("jwt-test-user")).thenReturn(user);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/users/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"jwt-test-user\",\"password\":\"" + testPassword + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(1, jwtUtil.parseToken(response.body()));
        assertEquals(200, get("/devices?status=AVAILABLE", response.body()));
        verify(deviceMapper).getDeviceByStatus("AVAILABLE");
    }

    @Test
    void publicRegistrationRemainsAccessible() throws Exception {
        when(userMapper.insertUser(any(User.class))).thenReturn(1);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/users/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"jwt-test-new-user\",\"password\":\"" + UUID.randomUUID() + "\"}"))
                .build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        verify(userMapper).insertUser(any(User.class));
    }

    @Test
    void filterOrderAndRegistrationAreCorrect() {
        List<? extends Class<?>> types = securityFilterChain.getFilters().stream()
                .map(filter -> (Class<?>) filter.getClass()).toList();
        assertTrue(types.indexOf(SecurityContextHolderFilter.class) < types.indexOf(JwtAuthenticationFilter.class));
        assertTrue(types.indexOf(JwtAuthenticationFilter.class) < types.indexOf(AuthorizationFilter.class));
        assertEquals(1, types.stream().filter(JwtAuthenticationFilter.class::equals).count());
        assertNull(servletContext.getFilterRegistration("jwtAuthenticationFilter"));
    }

    @Test
    void frontendAssetsArePublicButApisRemainProtected() throws Exception {
        for (String path : List.of("/", "/index.html", "/assets/app.js", "/assets/app.css", "/favicon.svg"))
            assertEquals(200, get(path, null), path);
        assertEquals(401, get("/users/me", null));
        assertEquals(401, get("/devices/page?page=1&pageSize=8", null));
    }

    @Test
    void currentUserResponseNeverIncludesPasswordHash() throws Exception {
        User user = new User(); user.setId(1); user.setRole("USER"); user.setUsername("fixture");
        user.setPasswordHash("private-fixture-hash"); when(userMapper.selectById(1)).thenReturn(user);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/users/me"))
            .header("Authorization", "Bearer "+jwtUtil.generateToken(1)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
        assertEquals(3, json.size()); assertEquals("fixture", json.get("username").asText());
        assertFalse(json.has("passwordHash")); assertFalse(response.body().contains("private-fixture-hash"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, DeviceController.class, DeviceService.class,
            UserController.class, UserService.class})
    static class TestApplication {
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean DeviceMapper deviceMapper() { return mock(DeviceMapper.class); }
        @Bean SecretKey testSigningKey() { return Jwts.SIG.HS256.key().build(); }
        @Bean JwtUtil jwtUtil(SecretKey testSigningKey) {
            return new JwtUtil(Encoders.BASE64.encode(testSigningKey.getEncoded()), 60_000);
        }
        @Bean AtomicInteger errorStatus() { return new AtomicInteger(); }
        @Bean FilterRegistrationBean<Filter> errorDispatchProbe(AtomicInteger errorStatus) {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                errorStatus.set((Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
                chain.doFilter(request, response);
            });
            registration.setDispatcherTypes(DispatcherType.ERROR);
            registration.setOrder(-101);
            return registration;
        }
    }
}
