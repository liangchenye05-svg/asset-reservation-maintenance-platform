package com.example.equipmentmanagement;

import com.example.equipmentmanagement.testsupport.IsolatedDatabaseTest;
import com.example.equipmentmanagement.testsupport.TestDatabase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP/MySQL/Flyway. All fixtures and DDL are restricted to newly created test schemas. */
@IsolatedDatabaseTest
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "logging.level.root=OFF", "spring.main.banner-mode=off", "server.address=127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnabledIfSystemProperty(named="repair.maintenance.it", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairMaintenanceHttpIntegrationTest {
    static String SCHEMA;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        var database=TestDatabase.create("maintenance", TestDatabase.Init.EMPTY);
        SCHEMA=database.schema();
        database.register(registry, true);
        String key=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
        registry.add("app.jwt.secret",()->key);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    final Map<String,String> tokens=new HashMap<>();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeAll void accounts() throws Exception {
        var encoder=new BCryptPasswordEncoder();
        for(String name:List.of("user_a","user_b","admin_a","admin_b")) {
            String password=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)",name,encoder.encode(password),name.startsWith("admin")?"ADMIN":"USER");
            var response=call(null,"POST","/users/login",Map.of("username",name,"password",password));
            assertEquals(200,response.statusCode());tokens.put(name,response.body());
        }
    }
    HttpResponse<String> call(String who,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(who!=null)request.header("Authorization","Bearer "+tokens.get(who));
        if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> expect(String who,String method,String path,Object body,int status) throws Exception {
        var response=call(who,method,path,body);
        // Login credentials/tokens are never written to evidence.
        System.out.println("MAINTENANCE "+method+" "+path+" identity="+who+" HTTP="+response.statusCode()+
                (path.endsWith("login")?" body=[omitted]":" body="+response.body()));
        assertEquals(status,response.statusCode(),method+" "+path);return response;
    }
    int device(String status) {
        String code="fixture-"+UUID.randomUUID();
        jdbc.update("INSERT INTO device(device_code,name,location,status) VALUES(?,'Fixture device','Fixture lab',?)",code,status);
        return jdbc.queryForObject("SELECT id FROM device WHERE device_code=?",Integer.class,code);
    }
    int order(int device) throws Exception {
        String description="fixture-"+UUID.randomUUID();
        expect("user_a","POST","/repair-orders",Map.of("deviceId",device,"description",description,"userId",2,"actorId",4),200);
        return jdbc.queryForObject("SELECT id FROM repair_order WHERE description=?",Integer.class,description);
    }
    String state(String table,int id){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id);}
    int historyCount(int id){return jdbc.queryForObject("SELECT COUNT(*) FROM repair_order_history WHERE repair_order_id=?",Integer.class,id);}
    JsonNode history(String who,int id) throws Exception {return JSON.readTree(expect(who,"GET","/repair-orders/"+id+"/history",null,200).body());}
    int reservation(int device,int day) throws Exception {
        var start=LocalDateTime.now().plusDays(day).withNano(0);
        var body=Map.of("deviceId",device,"startTime",start.toString(),"endTime",start.plusHours(1).toString());
        return JSON.readTree(expect("user_a","POST","/reservations",body,201).body()).get("id").asInt();
    }
    @Test void historyAndDeviceStateFollowTheRealOperators() throws Exception {
        int d=device("IN_USE"),id=order(d);
        assertEquals("IN_USE",state("device",d));assertEquals(1,historyCount(id));
        expect("admin_a","PUT","/repair-orders/"+id+"/process",null,200);
        assertEquals("MAINTENANCE",state("device",d));
        assertEquals("IN_USE",jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,d));
        expect("admin_b","PUT","/repair-orders/"+id+"/complete",null,200);
        assertEquals("IN_USE",state("device",d));
        var events=history("user_a",id);assertEquals(3,events.size());
        assertEquals(List.of("CREATED","PROCESSED","COMPLETED"),java.util.stream.StreamSupport.stream(events.spliterator(),false).map(e->e.get("action").asText()).toList());
        assertEquals(1,events.get(0).get("actorId").asInt());assertEquals(3,events.get(1).get("actorId").asInt());assertEquals(4,events.get(2).get("actorId").asInt());
    }
    @Test void pendingSiblingPreventsPrematureRestoration() throws Exception {
        int d=device("AVAILABLE"),a=order(d),b=order(d);
        expect("admin_a","PUT","/repair-orders/"+a+"/process",null,200);
        expect("admin_a","PUT","/repair-orders/"+a+"/complete",null,200);
        assertEquals("MAINTENANCE",state("device",d));assertEquals("PENDING",state("repair_order",b));
        expect("admin_b","PUT","/repair-orders/"+b+"/process",null,200);
        expect("admin_b","PUT","/repair-orders/"+b+"/complete",null,200);
        assertEquals("AVAILABLE",state("device",d));assertNull(jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,d));
    }
    @Test void manuallyMaintainedDeviceIsNotSilentlyEnabled() throws Exception {
        int d=device("MAINTENANCE"),id=order(d);
        expect("admin_a","PUT","/repair-orders/"+id+"/process",null,200);
        expect("admin_a","PUT","/repair-orders/"+id+"/complete",null,200);
        assertEquals("MAINTENANCE",state("device",d));
    }
    @Test void approvedReservationsRemainWhileNewAndPendingApprovalsAreBlocked() throws Exception {
        int d=device("AVAILABLE"),approved=reservation(d,10),pending=reservation(d,12),id=order(d);
        expect("admin_a","PUT","/reservations/"+approved+"/approve",null,200);
        var impact=JSON.readTree(expect("admin_a","GET","/repair-orders/"+id+"/maintenance-impact",null,200).body());
        assertEquals(1,impact.get("approvedReservationCount").asInt());assertEquals(approved,impact.get("reservations").get(0).get("id").asInt());
        expect("user_a","GET","/repair-orders/"+id+"/maintenance-impact",null,403);
        expect("admin_a","PUT","/repair-orders/"+id+"/process",null,200);
        assertEquals("APPROVED",state("reservation",approved));assertEquals("PENDING",state("reservation",pending));
        var start=LocalDateTime.now().plusDays(14).withNano(0);
        expect("user_a","POST","/reservations",Map.of("deviceId",d,"startTime",start.toString(),"endTime",start.plusHours(1).toString()),409);
        expect("admin_a","PUT","/reservations/"+pending+"/approve",null,409);assertEquals("PENDING",state("reservation",pending));
        expect("user_a","PUT","/reservations/"+approved+"/cancel",null,200);
        expect("admin_a","PUT","/repair-orders/"+id+"/complete",null,200);
        expect("admin_a","PUT","/reservations/"+pending+"/approve",null,200);
    }
    @Test void historyAndProcessingRespectOwnershipAndRoles() throws Exception {
        int id=order(device("AVAILABLE"));
        history("user_a",id);history("admin_a",id);
        expect("user_b","GET","/repair-orders/"+id+"/history",null,403);
        expect(null,"GET","/repair-orders/"+id+"/history",null,401);
        expect("user_a","PUT","/repair-orders/"+id+"/process",null,403);
        expect("user_a","PUT","/repair-orders/"+id+"/complete",null,403);
        expect("user_a","GET","/repair-orders/2147483647/history",null,404);
        expect("user_a","GET","/repair-orders/0/history",null,400);assertEquals(1,historyCount(id));
    }
    @Test void legacyProcessingHasNoInventedHistoryOrPreviousState() throws Exception {
        int d=device("MAINTENANCE");jdbc.update("UPDATE device SET maintenance_restore_blocked=1 WHERE id=?",d);jdbc.update("INSERT INTO repair_order(device_id,user_id,description,status) VALUES(?,1,'legacy fixture','PROCESSING')",d);
        int id=jdbc.queryForObject("SELECT id FROM repair_order WHERE device_id=?",Integer.class,d);
        assertEquals(0,history("user_a",id).size());
        expect("admin_a","PUT","/repair-orders/"+id+"/complete",null,409);
        expect("admin_a","PUT","/repair-orders/"+id+"/complete",Map.of("reviewNote","Synthetic manual review; keep current device status"),200);
        assertEquals("MAINTENANCE",state("device",d));assertEquals(1,historyCount(id));
    }
    @Test void createRollsBackIfHistoryFails() throws Exception {
        int d=device("AVAILABLE"),before=jdbc.queryForObject("SELECT COUNT(*) FROM repair_order",Integer.class);
        String trigger="fail_create_history";
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON repair_order_history FOR EACH ROW BEGIN IF NEW.action='CREATED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic history failure'; END IF; END");
        try {expect("user_a","POST","/repair-orders",Map.of("deviceId",d,"description","synthetic rollback"),500);}
        finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM repair_order",Integer.class));assertEquals("AVAILABLE",state("device",d));
    }
    @Test void processRollsBackOrderDeviceAndHistoryIfHistoryFails() throws Exception {
        int d=device("AVAILABLE"),id=order(d);String trigger="fail_process_history";
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON repair_order_history FOR EACH ROW BEGIN IF NEW.action='PROCESSED' AND NEW.repair_order_id="+id+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic history failure'; END IF; END");
        try {expect("admin_a","PUT","/repair-orders/"+id+"/process",null,500);}
        finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("PENDING",state("repair_order",id));assertEquals("AVAILABLE",state("device",d));assertEquals(1,historyCount(id));
        assertNull(jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,d));
    }
    @Test void completionRollsBackHistoryAndOrderIfDeviceRestoreFails() throws Exception {
        int d=device("AVAILABLE"),id=order(d);expect("admin_a","PUT","/repair-orders/"+id+"/process",null,200);
        String trigger="fail_restore_device";
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON device FOR EACH ROW BEGIN IF OLD.id="+d+" AND NEW.status='AVAILABLE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic device failure'; END IF; END");
        try {expect("admin_a","PUT","/repair-orders/"+id+"/complete",null,500);}
        finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("PROCESSING",state("repair_order",id));assertEquals("MAINTENANCE",state("device",d));assertEquals(2,historyCount(id));
    }
    List<Integer> race(int device,String first,String second) throws Exception {
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var pool=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);long connectionId;
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")){r.next();connectionId=r.getLong(1);}
            try(var s=connection.prepareStatement("SELECT id FROM device WHERE id=? FOR UPDATE")){s.setInt(1,device);s.executeQuery().close();}
            var a=pool.submit(()->call("admin_a","PUT",first,null));var b=pool.submit(()->call("admin_b","PUT",second,null));
            int waiters=0;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            try {
                while(System.nanoTime()<end) {
                    waiters=jdbc.queryForObject("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits WHERE BLOCKING_THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=?)",Integer.class,connectionId);
                    if(waiters==2||a.isDone()||b.isDone())break;Thread.sleep(20);
                }
                assertEquals(2,waiters,"Both HTTP transactions must contend for the same device row");
            } finally {connection.rollback();}
            var statuses=List.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode());
            System.out.println("MAINTENANCE RACE waiters="+waiters+" first="+first+" second="+second+" HTTP="+statuses);return statuses;
        }
    }
    @Test void concurrentDuplicateProcessingAndCompletionHaveOneWinner() throws Exception {
        int d=device("AVAILABLE"),id=order(d);
        for(String action:List.of("process","complete")) {
            var codes=new ArrayList<>(race(d,"/repair-orders/"+id+"/"+action,"/repair-orders/"+id+"/"+action));
            Collections.sort(codes);assertEquals(List.of(200,400),codes);
        }
        assertEquals(3,historyCount(id));assertEquals("AVAILABLE",state("device",d));
    }
    @Test void concurrentSiblingCompletionRestoresOnlyAfterBothComplete() throws Exception {
        int d=device("AVAILABLE"),a=order(d),b=order(d);
        expect("admin_a","PUT","/repair-orders/"+a+"/process",null,200);expect("admin_b","PUT","/repair-orders/"+b+"/process",null,200);
        assertEquals(List.of(200,200),race(d,"/repair-orders/"+a+"/complete","/repair-orders/"+b+"/complete"));
        assertEquals("AVAILABLE",state("device",d));assertEquals(3,historyCount(a));assertEquals(3,historyCount(b));
    }
    @Test void reservationApprovalAndRepairProcessingShareTheSameLock() throws Exception {
        int d=device("AVAILABLE"),reservation=reservation(d,20),id=order(d);
        var result=race(d,"/reservations/"+reservation+"/approve","/repair-orders/"+id+"/process");
        assertTrue(result.get(0)==200||result.get(0)==409);assertEquals(200,result.get(1));
        assertEquals(result.get(0)==200?"APPROVED":"PENDING",state("reservation",reservation));assertEquals("MAINTENANCE",state("device",d));
    }
    @Test void flywayEmptyDatabaseMigratesOnceAndIndexesExist() {
        assertEquals(3,flyway.info().applied().length);assertEquals(0,flyway.migrate().migrationsExecuted);flyway.validate();
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME IN ('idx_repair_owner_status_id','idx_repair_status_id','idx_repair_device_status_id','idx_reservation_device_status_time')",Integer.class));
        var plan=jdbc.queryForList("EXPLAIN SELECT * FROM repair_order WHERE user_id=1 AND status='PENDING' ORDER BY id DESC LIMIT 8");
        assertTrue(plan.toString().contains("idx_repair_owner_status_id"));System.out.println("MAINTENANCE EXPLAIN "+plan);
    }
    @Test void existingV1DatabaseRequiresExplicitBaselineAndKeepsItsRows() throws Exception {
        var legacy=TestDatabase.create("migration", TestDatabase.Init.V1);
        try(var c=legacy.open();var s=c.createStatement()) {
            s.execute("INSERT INTO users(username,password_hash) VALUES('legacy_fixture','unused-test-login')");
            s.execute("INSERT INTO device(device_code,name,location,status) VALUES('LEGACY','Legacy fixture','Fixture lab','IN_USE')");
            s.execute("INSERT INTO repair_order(device_id,user_id,description,status) VALUES(1,1,'legacy fixture','PENDING')");
        }
        var migration=legacy.flyway();
        assertThrows(FlywayException.class,migration::migrate);migration.baseline();assertEquals(2,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);
        try(var c=legacy.open();var s=c.createStatement();var r=s.executeQuery("SELECT d.status,r.description,(SELECT COUNT(*) FROM repair_order_history) FROM device d JOIN repair_order r ON r.device_id=d.id")) {
            assertTrue(r.next());assertEquals("IN_USE",r.getString(1));assertEquals("legacy fixture",r.getString(2));assertEquals(0,r.getInt(3));
        }
    }
}
