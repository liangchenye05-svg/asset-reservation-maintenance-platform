package com.example.equipmentmanagement;

import com.example.equipmentmanagement.testsupport.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only B3-created synthetic schemas. Migrate actual V2 fixtures before serving HTTP. */
@IsolatedDatabaseTest
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "logging.level.root=OFF","spring.main.banner-mode=off","server.address=127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnabledIfSystemProperty(named="repair.legacy.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairLegacyReviewHttpIntegrationTest {
    static TestDatabase database;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Map<String,String> PASSWORDS=new HashMap<>();
    static final Map<String,Integer> ORDERS=new HashMap<>();
    static boolean originalValuesPreserved;
    static int migrated,historyBefore,historyAfter;

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        database=TestDatabase.create("legacy",TestDatabase.Init.EMPTY);
        var v2=Flyway.configure().configuration(database.flyway().getConfiguration()).target("2").load();
        assertEquals(2,v2.migrate().migrationsExecuted);
        Map<String,String> before=new LinkedHashMap<>();Map<String,String> selects=new LinkedHashMap<>();
        try(var c=database.open()) {
            for(String name:List.of("b2_user","b2_other","b2_admin","b2_admin2")) {
                String password=UUID.randomUUID().toString();PASSWORDS.put(name,password);
                try(var s=c.prepareStatement("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)")) {
                    s.setString(1,name);s.setString(2,new BCryptPasswordEncoder().encode(password));s.setString(3,name.contains("admin")?"ADMIN":"USER");s.executeUpdate();
                }
            }
            for(String name:List.of("empty","pending","trusted","partial","unproven","rollback","validation","siblings","race","browser","browsermobile","broken","completefull","completepartial","pendingfull","fullnosnapshot")) {
                int device;
                try(var s=c.prepareStatement("INSERT INTO device(device_code,name,location,status,status_before_maintenance) VALUES(?,?,'Synthetic lab',?,?)",Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1,"B2-"+name);s.setString(2,"B2 "+name);s.setString(3,name.equals("pending")?"AVAILABLE":"MAINTENANCE");
                    s.setString(4,Set.of("trusted","partial","unproven").contains(name)?"IN_USE":null);s.executeUpdate();
                    try(var r=s.getGeneratedKeys()){r.next();device=r.getInt(1);}
                }
                try(var s=c.prepareStatement("INSERT INTO repair_order(device_id,user_id,description,status,created_at,update_at) VALUES(?,1,?,?, '2026-01-02 03:04:05','2026-02-03 04:05:06')",Statement.RETURN_GENERATED_KEYS)) {
                    s.setInt(1,device);s.setString(2,"B2 "+name);s.setString(3,Set.of("pending","pendingfull").contains(name)?"PENDING":name.startsWith("complete")?"COMPLETED":"PROCESSING");s.executeUpdate();
                    try(var r=s.getGeneratedKeys()){r.next();ORDERS.put(name,r.getInt(1));}
                }
                try(var s=c.prepareStatement("INSERT INTO repair_order_history(repair_order_id,actor_id,action,from_status,to_status) VALUES(?,?,?,?,?)")) {
                    if(Set.of("trusted","broken","completefull","pendingfull","fullnosnapshot").contains(name)) {s.setInt(1,ORDERS.get(name));s.setInt(2,1);s.setString(3,"CREATED");s.setString(4,null);s.setString(5,"PENDING");s.executeUpdate();}
                    if(Set.of("trusted","partial","completefull","completepartial","fullnosnapshot").contains(name)) {s.setInt(1,ORDERS.get(name));s.setInt(2,3);s.setString(3,"PROCESSED");s.setString(4,"PENDING");s.setString(5,"PROCESSING");s.executeUpdate();}
                    if(name.startsWith("complete")) {s.setInt(1,ORDERS.get(name));s.setInt(2,3);s.setString(3,"COMPLETED");s.setString(4,"PROCESSING");s.setString(5,"COMPLETED");s.executeUpdate();}
                }
            }
            // Snapshot every original column, including timestamps and real V2 history.
            for(String table:List.of("users","device","reservation","repair_order","repair_order_history")) {
                List<String> columns=new ArrayList<>();
                try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" LIMIT 0")) {
                    for(int i=1;i<=r.getMetaData().getColumnCount();i++)columns.add("`"+r.getMetaData().getColumnName(i)+"`");
                }
                String sql="SELECT "+String.join(",",columns)+" FROM "+table+" ORDER BY id";selects.put(table,sql);before.put(table,fingerprint(c,sql));
            }
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM repair_order_history")){r.next();historyBefore=r.getInt(1);}
        }
        migrated=database.flyway().migrate().migrationsExecuted;
        try(var c=database.open()) {
            for(String table:selects.keySet())assertEquals(before.get(table),fingerprint(c,selects.get(table)),"V3 must preserve all old values: "+table);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM repair_order_history")){r.next();historyAfter=r.getInt(1);}
        }
        originalValuesPreserved=true;
        database.register(registry,true);
        String key=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());registry.add("app.jwt.secret",()->key);
    }
    static String fingerprint(Connection c,String sql) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var s=c.createStatement();var r=s.executeQuery(sql)) {
            while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
                String value=r.getString(i);digest.update((value==null?"N;":"S"+value.length()+":"+value+";").getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final Map<String,String> tokens=new HashMap<>();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeAll void login() throws Exception {
        for(var account:PASSWORDS.entrySet()) {
            var response=call(null,"POST","/users/login",Map.of("username",account.getKey(),"password",account.getValue()));
            assertEquals(200,response.statusCode());tokens.put(account.getKey(),response.body());
        }
    }
    HttpResponse<String> call(String who,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(who!=null)request.header("Authorization","Bearer "+tokens.get(who));
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode expect(String who,String method,String path,Object body,int code) throws Exception {
        var response=call(who,method,path,body);System.out.println("B2_HTTP "+method+" "+path+" identity="+who+" expected="+code+" actual="+response.statusCode());
        assertEquals(code,response.statusCode(),method+" "+path+"; response and credentials omitted");
        return code==200?JSON.readTree(response.body()):null;
    }
    int deviceOf(int id){return jdbc.queryForObject("SELECT device_id FROM repair_order WHERE id=?",Integer.class,id);}
    String state(String table,int id){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id);}
    int count(int id){return jdbc.queryForObject("SELECT COUNT(*) FROM repair_order_history WHERE repair_order_id=?",Integer.class,id);}
    JsonNode detail(int id) throws Exception {return expect("b2_user","GET","/repair-orders/"+id,null,200);}
    JsonNode history(int id) throws Exception {return expect("b2_user","GET","/repair-orders/"+id+"/history",null,200);}
    String complete(int id){return "/repair-orders/"+id+"/complete";}

    @Test void v3PreservesOriginalColumnsAndHistoryAndRunsOnlyOnce() throws Exception {
        assertTrue(originalValuesPreserved);assertEquals(1,migrated);assertEquals(historyBefore,historyAfter);assertEquals(12,historyBefore);
        var flyway=database.flyway();flyway.validate();assertEquals(3,flyway.info().applied().length);assertEquals(0,flyway.migrate().migrationsExecuted);
        assertTrue(detail(ORDERS.get("completefull")).get("historyComplete").asBoolean());
        assertFalse(detail(ORDERS.get("completepartial")).get("historyComplete").asBoolean());
        assertTrue(detail(ORDERS.get("pendingfull")).get("historyComplete").asBoolean());
        System.out.println("B2_MIGRATION originalColumnsPreserved=true historyBefore=12 historyAfter=12 V3Executed=1 repeat=0");
    }
    @Test void missingSnapshotRequiresRealReviewAndNeverInventsCreation() throws Exception {
        for(String name:List.of("empty","fullnosnapshot")) {
            int id=ORDERS.get(name),device=deviceOf(id),oldCount=count(id);boolean completeHistory=name.equals("fullnosnapshot");
            assertEquals(completeHistory,detail(id).get("historyComplete").asBoolean());assertTrue(detail(id).get("manualReviewRequired").asBoolean());
            expect("b2_admin","PUT",complete(id),null,409);assertEquals("PROCESSING",state("repair_order",id));assertEquals(oldCount,count(id));
            expect("b2_admin","PUT",complete(id),Map.of("reviewNote","Checked current conditions; retain maintenance and coordinate bookings"),200);
            assertEquals("MAINTENANCE",state("device",device));assertNull(jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,device));
            assertEquals(completeHistory,detail(id).get("historyComplete").asBoolean());assertFalse(detail(id).get("manualReviewRequired").asBoolean());
            var events=history(id);assertEquals(oldCount+1,events.size());var event=events.get(events.size()-1);
            assertEquals("COMPLETED",event.get("action").asText());assertEquals(3,event.get("actorId").asInt());assertTrue(event.get("reviewNote").asText().startsWith("Checked"));
        }
    }

    @Test void creationAloneCannotHideMissingAcceptance() throws Exception {
        int id=ORDERS.get("broken");assertFalse(detail(id).get("historyComplete").asBoolean());assertTrue(detail(id).get("manualReviewRequired").asBoolean());
        expect("b2_admin","PUT",complete(id),null,409);expect("b2_admin","PUT",complete(id),Map.of("reviewNote","Creation exists but acceptance history is missing; keep device status"),200);
        assertFalse(detail(id).get("historyComplete").asBoolean());assertEquals(2,count(id));assertEquals("MAINTENANCE",state("device",deviceOf(id)));
    }
    @Test void oldPendingStaysIncompleteAfterActualAcceptanceAndCompletion() throws Exception {
        int id=ORDERS.get("pending");expect("b2_admin","PUT","/repair-orders/"+id+"/process",null,200);
        expect("b2_admin","PUT",complete(id),null,200);assertEquals("AVAILABLE",state("device",deviceOf(id)));
        assertFalse(detail(id).get("historyComplete").asBoolean());assertEquals(2,history(id).size());assertEquals("PROCESSED",history(id).get(0).get("action").asText());
    }
    @Test void actualV2CreationIsRecognizedAndNormalCompletionRemainsCompatible() throws Exception {
        int id=ORDERS.get("trusted");assertTrue(detail(id).get("historyComplete").asBoolean());assertFalse(detail(id).get("manualReviewRequired").asBoolean());
        expect("b2_admin","PUT",complete(id),null,200);assertEquals("IN_USE",state("device",deviceOf(id)));assertEquals(3,history(id).size());
    }
    @Test void partialHistoryWithRecordedAcceptanceIsNotRelabelledComplete() throws Exception {
        int id=ORDERS.get("partial");assertFalse(detail(id).get("historyComplete").asBoolean());assertFalse(detail(id).get("manualReviewRequired").asBoolean());
        expect("b2_admin","PUT",complete(id),null,200);assertEquals("IN_USE",state("device",deviceOf(id)));assertFalse(detail(id).get("historyComplete").asBoolean());assertEquals(2,count(id));
    }
    @Test void snapshotWithoutAcceptanceEvidenceDoesNotAuthorizeRestoration() throws Exception {
        int id=ORDERS.get("unproven"),device=deviceOf(id);assertTrue(detail(id).get("manualReviewRequired").asBoolean());
        expect("b2_admin","PUT",complete(id),null,409);expect("b2_admin","PUT",complete(id),Map.of("reviewNote","Cannot establish original state; keep current status"),200);
        assertEquals("MAINTENANCE",state("device",device));assertEquals("IN_USE",jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,device));
    }
    @Test void authorityValidationAndRepeatedCompletionNeverWriteFalseHistory() throws Exception {
        int id=ORDERS.get("validation");var note=Map.of("reviewNote","Real synthetic review");
        expect(null,"PUT",complete(id),note,401);expect("b2_user","PUT",complete(id),note,403);expect("b2_other","GET","/repair-orders/"+id+"/history",null,403);
        for(Object body:List.of(Map.of(),Map.of("reviewNote","  "),Map.of("reviewNote","x".repeat(501))))
            expect("b2_admin","PUT",complete(id),body,((Map<?,?>)body).isEmpty()?409:400);
        assertEquals(0,count(id));assertEquals("PROCESSING",state("repair_order",id));
        expect("b2_admin","PUT",complete(id),Map.of("reviewNote","  Verified current state  ","actorId",2,"historyComplete",true,"manualReviewRequired",false),200);
        expect("b2_admin2","PUT",complete(id),note,400);assertEquals(1,count(id));var event=history(id).get(0);assertEquals(3,event.get("actorId").asInt());assertEquals("Verified current state",event.get("reviewNote").asText());assertFalse(detail(id).get("historyComplete").asBoolean());
    }
    @Test void newOrderRecordsFullHistoryAndIgnoresForgedMetadata() throws Exception {
        String code="new-"+UUID.randomUUID();jdbc.update("INSERT INTO device(device_code,name,location,status) VALUES(?,'New fixture','Lab','IN_USE')",code);
        int device=jdbc.queryForObject("SELECT id FROM device WHERE device_code=?",Integer.class,code);
        expect("b2_user","POST","/repair-orders",Map.of("deviceId",device,"description","B2 fresh","historyComplete",false,"userId",2),200);
        int id=jdbc.queryForObject("SELECT id FROM repair_order WHERE device_id=?",Integer.class,device);assertTrue(detail(id).get("historyComplete").asBoolean());
        expect("b2_admin","PUT","/repair-orders/"+id+"/process",null,200);expect("b2_admin","PUT",complete(id),null,200);
        assertEquals("IN_USE",state("device",device));var events=history(id);assertEquals(3,events.size());for(var event:events)assertTrue(event.get("reviewNote").isNull());
    }
    @Test void reviewHistoryFailureRollsBackCompletion() throws Exception {
        int id=ORDERS.get("rollback");jdbc.execute("CREATE TRIGGER b2_fail_review BEFORE INSERT ON repair_order_history FOR EACH ROW BEGIN IF NEW.repair_order_id="+id+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic audit failure'; END IF; END");
        try {expect("b2_admin","PUT",complete(id),Map.of("reviewNote","Synthetic failed review"),500);}
        finally {jdbc.execute("DROP TRIGGER b2_fail_review");}
        assertEquals("PROCESSING",state("repair_order",id));assertEquals(0,count(id));assertEquals("MAINTENANCE",state("device",deviceOf(id)));
    }
    @Test void laterSiblingCannotEraseLegacyUncertaintyOrEnableAutomaticRestore() throws Exception {
        int legacy=ORDERS.get("siblings"),device=deviceOf(legacy);
        expect("b2_user","POST","/repair-orders",Map.of("deviceId",device,"description","B2 sibling"),200);
        int sibling=jdbc.queryForObject("SELECT MAX(id) FROM repair_order WHERE device_id=?",Integer.class,device);
        expect("b2_admin","PUT","/repair-orders/"+sibling+"/process",null,200);assertTrue(detail(sibling).get("historyComplete").asBoolean());assertTrue(detail(sibling).get("manualReviewRequired").asBoolean());
        assertNull(jdbc.queryForObject("SELECT status_before_maintenance FROM device WHERE id=?",String.class,device));
        expect("b2_admin","PUT",complete(legacy),Map.of("reviewNote","Legacy checked; keep maintenance"),200);
        expect("b2_admin2","PUT",complete(sibling),null,409);expect("b2_admin2","PUT",complete(sibling),Map.of("reviewNote","Sibling checked; keep maintenance"),200);
        assertEquals("MAINTENANCE",state("device",device));assertEquals(1,jdbc.queryForObject("SELECT maintenance_restore_blocked FROM device WHERE id=?",Integer.class,device));assertFalse(detail(legacy).get("historyComplete").asBoolean());assertTrue(detail(sibling).get("historyComplete").asBoolean());
    }
    @Test void competingReviewedCompletionsRecordOnlyTheWinner() throws Exception {
        int id=ORDERS.get("race"),device=deviceOf(id);
        try(var lock=database.open();var pool=Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);long connection;
            try(var s=lock.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")){r.next();connection=r.getLong(1);}
            try(var s=lock.prepareStatement("SELECT id FROM device WHERE id=? FOR UPDATE")){s.setInt(1,device);s.executeQuery().close();}
            var first=pool.submit(()->call("b2_admin","PUT",complete(id),Map.of("reviewNote","Reviewer A")));
            var second=pool.submit(()->call("b2_admin2","PUT",complete(id),Map.of("reviewNote","Reviewer B")));
            int waiters=0;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            try {
                while(System.nanoTime()<end) {
                    waiters=jdbc.queryForObject("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits WHERE BLOCKING_THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=?)",Integer.class,connection);
                    if(waiters==2||first.isDone()||second.isDone())break;Thread.sleep(20);
                }
                assertEquals(2,waiters);
            } finally {lock.rollback();}
            var results=new ArrayList<>(List.of(first.get(20,TimeUnit.SECONDS).statusCode(),second.get(20,TimeUnit.SECONDS).statusCode()));Collections.sort(results);assertEquals(List.of(200,400),results);
            System.out.println("B2_RACE waiters="+waiters+" actual="+results);
        }
        assertEquals(1,count(id));assertEquals("MAINTENANCE",state("device",device));
    }

    @Test @EnabledIfSystemProperty(named="b2.browser",matches="true")
    void actualBrowserDisplaysPersistentGapAndManualReview() throws Exception {
        var process=new ProcessBuilder("node","src/test/frontend/legacy-review.browser.cjs").redirectErrorStream(true);
        process.environment().put("B2_BASE_URL","http://127.0.0.1:"+port);process.environment().put("B2_DB_SCHEMA",database.schema());
        process.environment().put("B2_ADMIN_PASSWORD",PASSWORDS.get("b2_admin"));process.environment().put("B2_USER_PASSWORD",PASSWORDS.get("b2_user"));process.environment().put("B2_ORDER_ID",ORDERS.get("browser").toString());process.environment().put("B2_MOBILE_ORDER_ID",ORDERS.get("browsermobile").toString());
        var child=process.start();String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);int exit=child.waitFor();
        assertFalse(output.contains(PASSWORDS.get("b2_admin")));assertFalse(output.contains(PASSWORDS.get("b2_user")));System.out.println(output);assertEquals(0,exit,"Browser acceptance; credentials omitted");
    }
    @AfterAll void close(){client.close();}
}
