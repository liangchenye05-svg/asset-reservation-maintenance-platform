package com.example.equipmentmanagement.security;

import com.example.equipmentmanagement.EquipmentManagementApplication;
import com.example.equipmentmanagement.service.ReservationService;
import com.example.equipmentmanagement.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP + real MySQL. No mock, no test transaction, no existing-row writes.
 * Creates a unique independent schema from the three source table structures only.
 * Cleans its committed fixture rows after verification; never drops a database.
 * Credentials and random JWT signing key stay in memory and are never printed.
 */
@SpringBootTest(classes=EquipmentManagementApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"logging.level.root=ERROR", "spring.main.banner-mode=off",
      "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@Import(ReservationCancelOwnershipIntegrationTest.ProbeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="reservation.cancel.it", matches="true")
class ReservationCancelOwnershipIntegrationTest {
    private static final String KEY=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
    private static final Map<String,String> ORIGINAL=new LinkedHashMap<>();
    private static final String SCHEMA="dev027_"+UUID.randomUUID().toString().replace("-", "");
    private static String sourceUrl, targetUrl, username, password;
    private static boolean initialized;
    private static final List<String> EVIDENCE=Collections.synchronizedList(new ArrayList<>());
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry r) throws Exception {
        Properties p=new Properties();
        try(var input=Files.newInputStream(Path.of("src/main/resources/application.properties"))) { p.load(input); }
        sourceUrl=p.getProperty("spring.datasource.url");
        username=resolve(p.getProperty("spring.datasource.username"));
        password=resolve(p.getProperty("spring.datasource.password"));
        String source=sourceUrl.substring(sourceUrl.lastIndexOf('/')+1).split("\\?")[0];
        if(!source.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unsupported source schema name");
        String suffix=sourceUrl.contains("?")?sourceUrl.substring(sourceUrl.indexOf('?')):"";
        targetUrl=sourceUrl.substring(0,sourceUrl.lastIndexOf('/')+1)+SCHEMA+suffix;
        try(Connection c=DriverManager.getConnection(sourceUrl,username,password); Statement s=c.createStatement()) {
            for(String t:List.of("users","device","reservation")) {
                try(ResultSet rs=s.executeQuery("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+t+"'")) {
                    assertTrue(rs.next()); assertEquals("InnoDB",rs.getString(1));
                }
                ORIGINAL.put(t,fingerprint(c,t));
            }
            try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN ('users','device','reservation')")) {
                rs.next(); assertEquals(0,rs.getInt(1));
            }
            // Source constraints are inspected before cloning the complete table definitions.
            // No source row data is copied.
            try(ResultSet rs=s.executeQuery("SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('users','device','reservation') AND REFERENCED_TABLE_NAME IS NOT NULL")) {
                while(rs.next()) recordEvidence("SOURCE_FK "+rs.getString(1)+"."+rs.getString(2)+" -> "+rs.getString(3));
            }
            s.executeUpdate("CREATE DATABASE `"+SCHEMA+"`");
            // Clone SHOW CREATE output, including foreign keys. Switch catalog first
            // so unqualified REFERENCES resolve within the independent schema.
            c.setCatalog(SCHEMA);
            for(String t:List.of("users","device","reservation")) {
                String ddl;
                try(ResultSet rs=s.executeQuery("SHOW CREATE TABLE `"+source+"`.`"+t+"`")) {
                    assertTrue(rs.next()); ddl=rs.getString(2);
                }
                ddl=ddl.replace("CREATE TABLE `"+t+"`", "CREATE TABLE `"+SCHEMA+"`.`"+t+"`")
                    .replace("`"+source+"`.","`"+SCHEMA+"`.")
                    .replaceAll("AUTO_INCREMENT=\\d+", "AUTO_INCREMENT=1");
                s.executeUpdate(ddl);
            }
            initialized=true;
            recordEvidence("ISOLATED_DATABASE "+SCHEMA+" source="+source+"; structures only; no source writes");
        }
        r.add("spring.datasource.url",()->targetUrl);
        r.add("app.jwt.secret",()->KEY);
    }
    private static String resolve(String value) {
        if(value!=null && value.startsWith("${") && value.endsWith("}")) {
            String name=value.substring(2,value.length()-1);
            String found=System.getenv(name);
            if(found==null) throw new IllegalStateException("Required database credential environment variable absent");
            return found;
        }
        return value;
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ReservationService service;
    @Autowired SqlProbe probe;
    private HttpClient client;
    private int a,b,admin,device;
    private String tokenA,tokenB,tokenAdmin;
    private LocalDateTime future;
    @BeforeAll void fixtures() throws Exception {
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String pass=UUID.randomUUID().toString();
        String hash=new BCryptPasswordEncoder().encode(pass);
        a=insert("INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","dev027-a",hash,"USER");
        b=insert("INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","dev027-b",hash,"USER");
        admin=insert("INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","dev027-admin",hash,"ADMIN");
        device=insert("INSERT INTO device(device_code,name,location,model) VALUES (?,?,?,?)","dev027-device","DEV-027 isolated test","TEST","TEST");
        tokenA=login("dev027-a",pass,a); tokenB=login("dev027-b",pass,b); tokenAdmin=login("dev027-admin",pass,admin);
        future=LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS);
        assertTrue(AopUtils.isAopProxy(service),"Service must retain its transaction proxy");
        recordEvidence("FIXTURES USER_A="+a+" USER_B="+b+" ADMIN="+admin+" DEVICE="+device+" future="+future+" port="+port);
        for(String t:List.of("users","device","reservation")) {
            var ddl=jdbc.queryForMap("SHOW CREATE TABLE `"+t+"`");
            recordEvidence("TEST_DDL "+ddl.get("Create Table"));
        }
    }
    private String login(String user,String pass,int id) throws Exception {
        String body="{\"username\":\""+user+"\",\"password\":\""+pass+"\"}";
        var req=HttpRequest.newBuilder(uri("/users/login")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=client.send(req,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),"Fixture login status (body omitted)");
        assertEquals(id,jwt.parseToken(response.body()));
        return response.body();
    }
    @Test void userBCannotCancelUserAFuturePending() throws Exception { check("B_CANCEL_A_PENDING",reservation("PENDING",future),tokenB,403,"PENDING"); }
    @Test void userACancelsOwnFuturePendingAndRepeatIsRejected() throws Exception {
        int id=reservation("PENDING",future);
        check("A_CANCEL_OWN_PENDING",id,tokenA,200,"CANCELLED");
        check("A_CANCEL_AGAIN",id,tokenA,400,"CANCELLED");
    }
    @Test void adminCancelsOthersFuturePending() throws Exception { check("ADMIN_CANCEL_A_PENDING",reservation("PENDING",future),tokenAdmin,200,"CANCELLED"); }
    @Test void ownerCancelsOwnFutureApproved() throws Exception { check("A_CANCEL_OWN_APPROVED",reservation("APPROVED",future),tokenA,200,"CANCELLED"); }
    @Test void adminCancelsOthersFutureApproved() throws Exception { check("ADMIN_CANCEL_A_APPROVED",reservation("APPROVED",future),tokenAdmin,200,"CANCELLED"); }
    @Test void otherUserCannotCancelApproved() throws Exception { check("B_CANCEL_A_APPROVED",reservation("APPROVED",future),tokenB,403,"APPROVED"); }
    @Test void startedPendingRejectedForOwnerAndAdmin() throws Exception {
        int id=reservation("PENDING",LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS));
        check("A_STARTED_PENDING",id,tokenA,400,"PENDING"); check("ADMIN_STARTED_PENDING",id,tokenAdmin,400,"PENDING");
    }
    @Test void startedApprovedRejectedForOwnerAndAdmin() throws Exception {
        int id=reservation("APPROVED",LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS));
        check("A_STARTED_APPROVED",id,tokenA,400,"APPROVED"); check("ADMIN_STARTED_APPROVED",id,tokenAdmin,400,"APPROVED");
    }
    @Test void cancelledRejectedForOwnerAndAdmin() throws Exception {
        int id=reservation("CANCELLED",future); check("A_ALREADY_CANCELLED",id,tokenA,400,"CANCELLED"); check("ADMIN_ALREADY_CANCELLED",id,tokenAdmin,400,"CANCELLED");
    }
    @Test void missingAuthenticationRejectedBeforeAndAfterValidRequest() throws Exception {
        int id=reservation("PENDING",future); check("NO_AUTH_BEFORE",id,null,401,"PENDING");
        check("AUTH_B_IN_BETWEEN",id,tokenB,403,"PENDING"); check("NO_AUTH_AFTER",id,null,401,"PENDING");
    }
    @Test void invalidAndEmptyBearerRejected() throws Exception {
        int id=reservation("PENDING",future); check("INVALID_JWT",id,"invalid-test-token",401,"PENDING"); check("EMPTY_BEARER",id,"",401,"PENDING");
    }
    @Test void failureAfterDatabaseUpdateRollsBackCancellation() throws Exception {
        int id=reservation("PENDING",future);
        probe.failAfterUpdate=true;
        try {
            check("ROLLBACK_AFTER_UPDATE",id,tokenA,500,"PENDING");
            assertTrue(probe.sawCancelledBeforeFailure,"Probe must see actual SQL update within transaction");
        } finally { probe.failAfterUpdate=false; }
    }
    @Test void notFoundReturns404() throws Exception {
        int actual=cancel(Integer.MAX_VALUE,tokenA).statusCode();
        recordEvidence("CANCEL_RESULT NOT_FOUND id="+Integer.MAX_VALUE+" HTTP="+actual+" expected=404 before=ABSENT after=ABSENT");
        assertEquals(404,actual);
    }
    @Test void waitingForDeviceLockMustRereadChangedStatus() throws Exception {
        race("REREAD_STATUS",false);
    }
    @Test void waitingForDeviceLockMustRereadChangedOwner() throws Exception {
        race("REREAD_OWNER",true);
    }
    @Test void concurrentOwnerCancellationsYieldOneSuccessAndOneBusinessRejection() throws Exception {
        int id=reservation("PENDING",future);
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Integer> action=()->{ start.await(); return cancel(id,tokenA).statusCode(); };
            Future<Integer> one=pool.submit(action),two=pool.submit(action); start.countDown();
            List<Integer> statuses=new ArrayList<>(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))); Collections.sort(statuses);
            String after=status(id);
            recordEvidence("CANCEL_RESULT CONCURRENT_OWNER id="+id+" HTTP="+statuses+" expected=[200, 400] before=PENDING after="+after);
            assertEquals(List.of(200,400),statuses); assertEquals("CANCELLED",after);
        }
    }
    private void race(String label,boolean changeOwner) throws Exception {
        int id=reservation("PENDING",future);
        try(Connection blocker=DriverManager.getConnection(targetUrl,username,password); var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(PreparedStatement ps=blocker.prepareStatement("SELECT id FROM device WHERE id=? FOR UPDATE")) {
                ps.setInt(1,device); try(ResultSet rs=ps.executeQuery()) { assertTrue(rs.next()); }
            }
            CountDownLatch reached=new CountDownLatch(1); probe.lockReached=reached;
            try {
                Future<HttpResponse<String>> result=pool.submit(()->cancel(id,tokenA));
                assertTrue(reached.await(10,TimeUnit.SECONDS),"Cancel must reach real device lock");
                // Our lock is still held: the HTTP transaction cannot pass FOR UPDATE.
                assertThrows(TimeoutException.class,()->result.get(200,TimeUnit.MILLISECONDS),"Device row lock must block cancellation");
                try(PreparedStatement ps=blocker.prepareStatement(changeOwner?
                        "UPDATE reservation SET user_id=? WHERE id=?":"UPDATE reservation SET status='CANCELLED' WHERE id=?")) {
                    if(changeOwner) { ps.setInt(1,b); ps.setInt(2,id); } else ps.setInt(1,id);
                    assertEquals(1,ps.executeUpdate());
                }
                blocker.commit();
                HttpResponse<String> response=result.get(15,TimeUnit.SECONDS);
                int expected=changeOwner?403:400;
                String after=status(id), wanted=changeOwner?"PENDING":"CANCELLED";
                int afterOwner=jdbc.queryForObject("SELECT user_id FROM reservation WHERE id=?",Integer.class,id);
                recordEvidence("CANCEL_RESULT "+label+" id="+id+" HTTP="+response.statusCode()+" expected="+expected+
                    " before=PENDING owner="+a+" committedWhileWaiting="+(changeOwner?"owner->"+b:"status->CANCELLED")+" after="+after+" afterOwner="+afterOwner);
                assertEquals(expected,response.statusCode()); assertEquals(wanted,after);
                assertEquals(changeOwner?b:a,afterOwner);
            } finally { probe.lockReached=null; blocker.rollback(); }
        }
    }
    private void check(String label,int id,String token,int expected,String wanted) throws Exception {
        Map<String,Object> before=jdbc.queryForMap("SELECT id,user_id,device_id,status,start_time,end_time FROM reservation WHERE id=?",id);
        long locksBefore=probe.locks;
        HttpResponse<String> response=cancel(id,token);
        Map<String,Object> after=jdbc.queryForMap("SELECT id,user_id,device_id,status,start_time,end_time FROM reservation WHERE id=?",id);
        recordEvidence("CANCEL_RESULT "+label+" id="+id+" HTTP="+response.statusCode()+" expected="+expected+
            " before="+before+" after="+after+" locks="+(probe.locks-locksBefore)+" body="+safeBody(response));
        assertEquals(expected,response.statusCode(),"HTTP status; request/credentials omitted");
        assertEquals(wanted,after.get("status"));
        if(expected==200) {
            assertEquals("1",response.body()); before.put("status","CANCELLED");
        }
        assertEquals(before,after,"Only successful status change is allowed");
        if(token==null || token.equals("") || token.equals("invalid-test-token")) assertEquals(locksBefore,probe.locks);
        else assertEquals(locksBefore+1,probe.locks);
        // Independent connection after response: reads committed state, outside HTTP transaction.
        try(Connection c=DriverManager.getConnection(targetUrl,username,password); PreparedStatement ps=c.prepareStatement("SELECT status FROM reservation WHERE id=?")) {
            ps.setInt(1,id); try(ResultSet rs=ps.executeQuery()) { assertTrue(rs.next()); assertEquals(wanted,rs.getString(1)); }
        }
        // A new transaction must be able to acquire the device lock after every outcome.
        try(Connection c=DriverManager.getConnection(targetUrl,username,password); Statement s=c.createStatement()) {
            c.setAutoCommit(false); s.execute("SET SESSION innodb_lock_wait_timeout=2");
            try(ResultSet rs=s.executeQuery("SELECT id FROM device WHERE id="+device+" FOR UPDATE")) { assertTrue(rs.next()); }
            c.rollback();
        }
    }
    private String safeBody(HttpResponse<String> response) {
        return response.statusCode()==200 || response.statusCode()==400 ? response.body() : "<security/error body omitted>";
    }
    private HttpResponse<String> cancel(int id,String token) throws Exception {
        var req=HttpRequest.newBuilder(uri("/reservations/"+id+"/cancel")).timeout(Duration.ofSeconds(12));
        if(token!=null) req.header("Authorization","Bearer "+token);
        return client.send(req.PUT(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    private int reservation(String status,LocalDateTime start) throws Exception {
        return insert("INSERT INTO reservation(device_id,user_id,start_time,end_time,status) VALUES (?,?,?,?,?)",device,a,start,start.plusHours(2),status);
    }
    private int insert(String sql,Object... values) throws Exception {
        try(Connection c=DriverManager.getConnection(targetUrl,username,password); PreparedStatement ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<values.length;i++) ps.setObject(i+1,values[i]);
            assertEquals(1,ps.executeUpdate()); try(ResultSet rs=ps.getGeneratedKeys()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }
    private String status(int id) { return jdbc.queryForObject("SELECT status FROM reservation WHERE id=?",String.class,id); }
    @AfterAll void verifySourceUnchanged() throws Exception {
        if(client!=null) client.close();
        if(initialized) try(Connection c=DriverManager.getConnection(sourceUrl,username,password)) {
            for(var entry:ORIGINAL.entrySet()) {
                String after=fingerprint(c,entry.getKey());
                assertEquals(entry.getValue(),after,"Source rows unchanged: "+entry.getKey());
                recordEvidence("SOURCE_TABLE "+entry.getKey()+" beforeRows="+entry.getValue().split(":")[0]+" afterRows="+after.split(":")[0]+" fingerprintMatch=true");
            }
            try (Connection target=DriverManager.getConnection(targetUrl,username,password);
                 Statement clean=target.createStatement()) {
                assertEquals(SCHEMA, target.getCatalog());
                for (String table : List.of("reservation", "device", "users")) {
                    clean.executeUpdate("DELETE FROM `"+table+"`");
                    try (ResultSet rows=clean.executeQuery("SELECT COUNT(*) FROM `"+table+"`")) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                    }
                }
            }
            recordEvidence("SOURCE_UNCHANGED users/device/reservation fingerprints match; isolated schema empty="+SCHEMA);
            recordEvidence("TRANSACTION_PROBE locks="+probe.locks+" active="+probe.allTransactionsActive+" READ_COMMITTED="+probe.allReadCommitted+" reservationSqlReads="+probe.reads);
            assertTrue(probe.allTransactionsActive); assertTrue(probe.allReadCommitted);
            Files.createDirectories(Path.of("verification/dev027"));
            Files.write(Path.of("verification/dev027/latest-evidence.txt"),EVIDENCE,StandardCharsets.UTF_8);
        }
    }
    private static void recordEvidence(String value) {
        EVIDENCE.add(value);
        System.out.println(value);
    }
    private static String fingerprint(Connection c,String table) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); int count=0;
        try(Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT * FROM `"+table+"` ORDER BY id")) {
            while(rs.next()) { count++; for(int col=1;col<=rs.getMetaData().getColumnCount();col++) {
                String value=rs.getString(col); String canonical=value==null?"N;":"S"+value.length()+":"+value+";";
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            } }
        }
        // Never print or include source row values or password hashes in assertions.
        return count+":"+HexFormat.of().formatHex(digest.digest());
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration { @Bean SqlProbe sqlProbe() { return new SqlProbe(); } }
    @Intercepts({
        @Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}),
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
    })
    static class SqlProbe implements Interceptor {
        volatile CountDownLatch lockReached;
        volatile long locks,reads;
        volatile boolean failAfterUpdate,sawCancelledBeforeFailure;
        volatile boolean allTransactionsActive=true,allReadCommitted=true;
        public Object intercept(Invocation invocation) throws Throwable {
            if(invocation.getTarget() instanceof Executor executor) {
                MappedStatement statement=(MappedStatement)invocation.getArgs()[0];
                Object result=invocation.proceed();
                if(failAfterUpdate && statement.getId().endsWith("ReservationMapper.cancelIfAllowed")) {
                    try(PreparedStatement ps=executor.getTransaction().getConnection().prepareStatement("SELECT status FROM reservation WHERE id=?")) {
                        ps.setObject(1,invocation.getArgs()[1]);
                        try(ResultSet rs=ps.executeQuery()) { sawCancelledBeforeFailure=rs.next() && "CANCELLED".equals(rs.getString(1)); }
                    }
                    throw new IllegalStateException("DEV-027 test-only failure after UPDATE; rollback expected");
                }
                return result;
            }
            StatementHandler handler=(StatementHandler)invocation.getTarget();
            String sql=handler.getBoundSql().getSql().toLowerCase(Locale.ROOT);
            if(sql.contains("from reservation") && sql.startsWith("select *")) reads++;
            if(sql.contains("from device") && sql.contains("for update")) {
                locks++;
                allTransactionsActive &= TransactionSynchronizationManager.isActualTransactionActive();
                allReadCommitted &= ((Connection)invocation.getArgs()[0]).getTransactionIsolation()==Connection.TRANSACTION_READ_COMMITTED;
                CountDownLatch latch=lockReached; if(latch!=null) latch.countDown();
            }
            return invocation.proceed();
        }
    }
}