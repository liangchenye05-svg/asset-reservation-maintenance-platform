package com.example.equipmentmanagement;

import com.example.equipmentmanagement.testsupport.IsolatedDatabaseTest;
import com.example.equipmentmanagement.testsupport.TestDatabase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with -Drepair.status.it=true. Requires local MySQL CREATE DATABASE privilege.
 * Builds schema.sql in a newly named database; never reads or writes business rows.
 * Credentials/tokens stay in memory. Synthetic fixtures are retained for inspection.
 */
@IsolatedDatabaseTest
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "logging.level.root=OFF", "spring.main.banner-mode=off", "server.address=127.0.0.1",
    "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnabledIfSystemProperty(named="repair.status.it", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairStatusHttpIntegrationTest {
    static String SCHEMA;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        var database=TestDatabase.create("status", TestDatabase.Init.CURRENT);
        SCHEMA=database.schema();
        database.register(registry, false);
        String key=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
        registry.add("app.jwt.secret",()->key);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<String,String> tokens=new HashMap<>();
    @BeforeAll void fixtures() throws Exception {
        var encoder=new BCryptPasswordEncoder();
        for(String name:List.of("fixture_a","fixture_b","fixture_empty","fixture_admin")) {
            String password=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)",name,encoder.encode(password),name.endsWith("admin")?"ADMIN":"USER");
            var request=HttpRequest.newBuilder(URI.create(base()+"/users/login"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username",name,"password",password)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),"Fixture login (credentials omitted)");
            tokens.put(name,response.body());
        }
        jdbc.update("INSERT INTO device(device_code,name,location) VALUES('DAY12','Fixture device','Fixture lab')");
        for(int userId:List.of(1,2)) for(String status:List.of("PENDING","PROCESSING","COMPLETED"))
            jdbc.update("INSERT INTO repair_order(device_id,user_id,description,status) VALUES(1,?,?,?)",userId,"fixture-"+userId+"-"+status,status);
        System.out.println("DAY12 isolated MySQL schema initialized; four synthetic accounts, six repair orders");
    }
    String base(){return "http://127.0.0.1:"+port;}
    HttpResponse<String> get(String account,String path,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10));
        if(account!=null) request.header("Authorization","Bearer "+tokens.get(account));
        var response=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
        System.out.println("DAY12 GET "+path+" role="+(account==null?"ANONYMOUS":account)+" HTTP="+response.statusCode()+" response="+(path.endsWith(".js")?"[static JavaScript verified separately]":response.body()));
        assertEquals(expected,response.statusCode());return response;
    }
    @Test void statusesAndLegacyListPreserveOwnership() throws Exception {
        for(String account:List.of("fixture_a","fixture_b","fixture_admin")) {
            boolean admin=account.endsWith("admin");int userId=account.endsWith("a")?1:2;
            for(String status:List.of("","PENDING","PROCESSING","COMPLETED")) {
                var rows=JSON.readTree(get(account,"/repair-orders"+(status.isEmpty()?"":"?status="+status),200).body());
                assertEquals((admin?2:1)*(status.isEmpty()?3:1),rows.size());
                int previous=Integer.MAX_VALUE;
                for(var row:rows) {
                    assertTrue(row.get("id").asInt()<previous);previous=row.get("id").asInt();
                    if(!admin)assertEquals(userId,row.get("userId").asInt());
                    if(!status.isEmpty())assertEquals(status,row.get("status").asText());
                }
            }
        }
        var rows=JSON.readTree(get("fixture_a","/repair-orders?status=PENDING&userId=2&role=ADMIN",200).body());
        assertEquals(1,rows.size());assertEquals(1,rows.get(0).get("userId").asInt());
    }
    @Test void emptyAndInvalidStatuses() throws Exception {
        for(String status:List.of("PENDING","PROCESSING","COMPLETED"))
            assertEquals("[]",get("fixture_empty","/repair-orders?status="+status,200).body());
        for(String status:List.of("INVALID","pending","","%20"))
            assertEquals("状态不合法",get("fixture_a","/repair-orders?status="+status,400).body());
    }
    @Test void anonymousRemainsUnauthorized() throws Exception {
        get(null,"/repair-orders?status=PENDING",401);
    }
    @Test void frontendHandlersUseRealHttpForEachIdentity() throws Exception {
        var script=get(null,"/assets/app.js",200).body();
        assertEquals(Files.readString(Path.of("src/main/resources/static/assets/app.js")),script);
        for(String account:List.of("fixture_a","fixture_b","fixture_empty","fixture_admin")) {
            var process=new ProcessBuilder("node","--test","src/test/frontend/repair-status.test.cjs").redirectErrorStream(true);
            process.environment().put("TEST_DB_SCHEMA",SCHEMA);
            process.environment().put("DAY12_BASE_URL",base());
            process.environment().put("DAY12_TOKEN",tokens.get(account));
            process.environment().put("DAY12_ROLE",account.endsWith("admin")?"ADMIN":"USER");
            process.environment().put("DAY12_EXPECT_PER_STATUS",account.endsWith("admin")?"2":account.endsWith("empty")?"0":"1");
            // Use a file to avoid a subprocess pipe blocking on test output.
            Path log=Path.of("target/day12-frontend-"+account+".txt");
            process.redirectOutput(log.toFile());var child=process.start();
            assertTrue(child.waitFor(60,TimeUnit.SECONDS),"Frontend HTTP test timed out");
            assertEquals(0,child.exitValue(),()->"Frontend failure: "+log);
            System.out.println("DAY12 actual app.js events over HTTP: "+account+" passed");
        }
    }
    @AfterAll void verifyFixturesUnchanged() {
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM repair_order",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM reservation",Integer.class));
    }
}
