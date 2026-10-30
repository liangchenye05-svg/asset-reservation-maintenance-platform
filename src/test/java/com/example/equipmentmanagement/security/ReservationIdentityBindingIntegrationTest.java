package com.example.equipmentmanagement.security;

import com.example.equipmentmanagement.entity.Reservation;
import com.example.equipmentmanagement.entity.User;
import com.example.equipmentmanagement.mapper.ReservationMapper;
import com.example.equipmentmanagement.mapper.UserMapper;
import com.example.equipmentmanagement.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Opt-in test against the configured real MySQL database. MockMvc runs the real
 * security filters, controllers, services and MyBatis mappers on the transaction
 * thread; no authentication or database bean is mocked. All fixture rows and
 * reservation INSERTs roll back. Never use andDo(print()) or print token bodies.
 */
@SpringBootTest(properties = {
        "logging.level.root=ERROR", "spring.main.banner-mode=off",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
@Rollback
@EnabledIfSystemProperty(named = "reservation.identity.it", matches = "true")
class ReservationIdentityBindingIntegrationTest {
    // Independent, memory-only key: test tokens are valid only in this test context.
    // Do not depend on, read or change the running application's JWT_SECRET.
    private static final String TEST_SIGNING_KEY = Encoders.BASE64.encode(
            Jwts.SIG.HS256.key().build().getEncoded());

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> TEST_SIGNING_KEY);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserMapper users;
    @Autowired ReservationMapper reservations;

    private final Map<String, String> before = new LinkedHashMap<>();
    private int deviceId;
    private String deviceCode;
    private String fixtureUsername;
    private LocalDateTime start;

    @BeforeTransaction
    void checkDatabaseAndSnapshot() {
        // Fail BEFORE inserting anything if rollback cannot protect these tables.
        for (String table : List.of("users", "device", "reservation")) {
            String engine = jdbc.queryForObject(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                    String.class, table);
            assertEquals("InnoDB", engine, "This test requires transactional tables");
            before.put(table, fingerprint(table));
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() " +
                        "AND EVENT_OBJECT_TABLE IN ('users','device','reservation')", Integer.class),
                "Review trigger side effects before running this test");
        User user = users.selectById(1);
        assertNotNull(user, "The real user with id=1 must exist");
        assertEquals("USER", user.getRole(), "Use an ordinary USER, not an administrator");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() " +
                        "AND TABLE_NAME='reservation' AND COLUMN_NAME='status'", String.class));
    }

    @BeforeEach
    void createIsolatedDevice() {
        deviceCode = "codex-bind-" + UUID.randomUUID().toString().substring(0, 16);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        assertEquals(1, jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO device(device_code,name,location,model) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, deviceCode);
            statement.setString(2, "Identity binding test");
            statement.setString(3, "Transactional fixture");
            statement.setString(4, "TEST");
            return statement;
        }, key));
        deviceId = key.getKey().intValue();
        start = LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(0, rowCount());
        assertEquals(0, reservations.countConflicts(deviceId, start, start.plusHours(10)));
    }

    @Test
    void spoofedUser999IsReplacedWithRealUser1AndStatusIsPending() throws Exception {
        String token = jwtUtil.generateToken(1);
        assertEquals(1, jwtUtil.parseToken(token));
        createAndCheck(token, "999", 0, 1);
    }

    @Test
    void omittedClientUserIdStillUsesRealUser1() throws Exception {
        createAndCheck(jwtUtil.generateToken(1), null, 0, 1);
    }

    @Test
    void missingJwtIsRejectedBeforeAndAfterAnAuthenticatedRequest() throws Exception {
        assertStatus(401, reservationPost(null, "999", 0));
        assertEquals(0, rowCount(), "Anonymous request must not insert a row");
        createAndCheck(jwtUtil.generateToken(1), "999", 0, 1);
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null,
                "Authentication must be cleared after the request");
        assertStatus(401, reservationPost(null, "999", 2));
        assertEquals(1, rowCount(), "The previous JWT must not authorize the next request");
        System.out.println("IDENTITY_RESULT no JWT: HTTP 401 before/after JWT; no extra database rows");
    }

    @Test
    void registeredAndLoggedInSecondUserDoesNotMixWithUser1() throws Exception {
        fixtureUsername = "codex-bind-" + UUID.randomUUID().toString().substring(0, 16);
        String password = UUID.randomUUID().toString(); // Disposable, memory-only credential.
        String credentials = "{\"username\":\"" + fixtureUsername + "\",\"password\":\"" + password + "\"}";
        MvcResult registered = mvc.perform(post("/users/register")
                .contentType("application/json").content(credentials)).andReturn();
        assertStatus(200, registered);
        assertEquals("1", registered.getResponse().getContentAsString());
        User second = users.selectByUsername(fixtureUsername);
        assertNotNull(second);
        assertNotEquals(1, second.getId());
        assertEquals("USER", second.getRole());
        MvcResult login = mvc.perform(post("/users/login")
                .contentType("application/json").content(credentials)).andReturn();
        assertStatus(200, login);
        String secondToken = login.getResponse().getContentAsString();
        assertTrue(second.getId().equals(jwtUtil.parseToken(secondToken)), "Login must issue the correct identity");
        String firstToken = jwtUtil.generateToken(1); // No real password needed or modified.
        createAndCheck(firstToken, "999", 0, 1);
        createAndCheck(secondToken, "1", 2, second.getId());
        createAndCheck(firstToken, second.getId().toString(), 4, 1);
        createAndCheck(secondToken, "999", 6, second.getId());
        assertStatus(401, reservationPost(null, "999", 8));
        assertEquals(4, rowCount());
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null);
        System.out.println("IDENTITY_RESULT alternating authenticated users: 1 -> " + second.getId()
                + " -> 1 -> " + second.getId() + "; all PENDING; anonymous rejected");
    }

    @Test
    void tamperedAndEmptyJwtCannotCreateReservations() throws Exception {
        String[] parts = jwtUtil.generateToken(1).split("\\.");
        char changed = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = changed + parts[2].substring(1);
        assertStatus(401, reservationPost(String.join(".", parts), "999", 0));
        assertStatus(401, reservationPost("", "999", 0));
        assertEquals(0, rowCount());
        System.out.println("IDENTITY_RESULT tampered JWT: HTTP 401; empty Bearer: HTTP 401; no database rows");
    }

    private MvcResult reservationPost(String token, String suppliedUserId, int hourOffset) throws Exception {
        LocalDateTime from = start.plusHours(hourOffset);
        String json = "{\"deviceId\":" + deviceId
                + (suppliedUserId == null ? "" : ",\"userId\":" + suppliedUserId)
                + ",\"startTime\":\"" + from + "\",\"endTime\":\"" + from.plusHours(1)
                + "\",\"status\":\"APPROVED\"}";
        var request = post("/reservations").contentType("application/json").content(json);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private void createAndCheck(String token, String suppliedUserId, int offset, int expectedUserId) throws Exception {
        int previousCount = rowCount();
        MvcResult response = reservationPost(token, suppliedUserId, offset);
        assertStatus(201, response);
        assertTrue(response.getResponse().getHeader("Location").startsWith("/reservations/"));
        assertEquals(previousCount + 1, rowCount());
        LocalDateTime from = start.plusHours(offset);
        var rows = jdbc.queryForList("SELECT id,user_id,status FROM reservation WHERE device_id=? AND start_time=?",
                deviceId, from);
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals(expectedUserId, ((Number) row.get("user_id")).intValue());
        assertEquals("PENDING", row.get("status"));
        Reservation mapped = reservations.selectById(((Number) row.get("id")).intValue());
        assertEquals(expectedUserId, mapped.getUserId());
        assertEquals(deviceId, mapped.getDeviceId());
        assertEquals(from, mapped.getStartTime());
        assertEquals(from.plusHours(1), mapped.getEndTime());
        assertEquals("PENDING", mapped.getStatus());
        System.out.println("IDENTITY_RESULT HTTP 201: deviceId=" + deviceId + ", requested userId="
                + suppliedUserId + ", saved userId=" + expectedUserId + ", status=PENDING, start=" + from);
    }

    private void assertStatus(int expected, MvcResult result) {
        // Never use MockMvc result printing: the request can contain a JWT or password.
        assertEquals(expected, result.getResponse().getStatus(), "Unexpected HTTP status (body intentionally omitted)");
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE device_id=?", Integer.class, deviceId);
    }

    @AfterTransaction
    void verifyRollbackAndExistingData() {
        for (var entry : before.entrySet()) {
            assertEquals(entry.getValue(), fingerprint(entry.getKey()),
                    "Existing table rows must remain unchanged: " + entry.getKey());
        }
        if (deviceCode != null) assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE device_code=?", Integer.class, deviceCode));
        if (fixtureUsername != null) assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username=?", Integer.class, fixtureUsername));
        if (deviceCode != null) assertEquals(0, rowCount());
        System.out.println("ROLLBACK_VERIFIED users/device/reservation row fingerprints unchanged; fixtures absent");
    }

    private String fingerprint(String table) {
        // Only fixed table names from this class are used. Row contents, including
        // password hashes, remain in memory and are never printed or asserted.
        return jdbc.query("SELECT * FROM " + table + " ORDER BY id", result -> {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
            int count = 0;
            while (result.next()) {
                count++;
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    String value = result.getString(column);
                    String canonical = value == null ? "N;" : "S" + value.length() + ":" + value + ";";
                    digest.update(canonical.getBytes(StandardCharsets.UTF_8));
                }
            }
            return count + ":" + HexFormat.of().formatHex(digest.digest());
        });
    }
}
