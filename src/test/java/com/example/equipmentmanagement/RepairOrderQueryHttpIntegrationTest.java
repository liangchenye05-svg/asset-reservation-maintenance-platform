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
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and MySQL; creates only a random, dedicated schema. No daily rows are accessed. */
@IsolatedDatabaseTest
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "logging.level.root=OFF", "spring.main.banner-mode=off", "server.address=127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnabledIfSystemProperty(named="repair.query.it", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairOrderQueryHttpIntegrationTest {
    static String SCHEMA;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        var database=TestDatabase.create("query", TestDatabase.Init.CURRENT);
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
            var request=HttpRequest.newBuilder(URI.create(base()+"/users/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username",name,"password",password)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),"Synthetic login; credentials omitted");tokens.put(name,response.body());
        }
        jdbc.update("INSERT INTO device(device_code,name,location) VALUES('QUERY-ONE','Fixture device','Fixture lab'),('QUERY-TWO','Pump device','Fixture lab')");
        for(int user:List.of(1,2)) for(String status:List.of("PENDING","PROCESSING","COMPLETED")) for(int i=0;i<7;i++)
            jdbc.update("INSERT INTO repair_order(device_id,user_id,description,status) VALUES(?,?,?,?)",i%2+1,user,
                    i==0?"needle literal %_":"fixture-"+user+"-"+status+"-"+i,status);
    }
    String base(){return "http://127.0.0.1:"+port;}
    String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    tools.jackson.databind.JsonNode get(String account,String path,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10));
        if(account!=null)request.header("Authorization","Bearer "+tokens.get(account));
        var response=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
        System.out.println("QUERY GET "+path+" identity="+(account==null?"ANONYMOUS":account)+" HTTP="+response.statusCode()+" body="+response.body());
        assertEquals(expected,response.statusCode());
        return expected==200?JSON.readTree(response.body()):null;
    }
    @Test void paginationDoesNotRepeatOrLeakRows() throws Exception {
        Set<Integer> ids=new HashSet<>();int previous=Integer.MAX_VALUE;
        for(int page=1;page<=3;page++) {
            var result=get("fixture_a","/repair-orders/page?page="+page+"&pageSize=8",200);
            assertEquals(21,result.get("total").asInt());assertEquals(page,result.get("page").asInt());
            assertEquals(page==3?5:8,result.get("records").size());
            for(var row:result.get("records")) {
                assertEquals(1,row.get("userId").asInt());int id=row.get("id").asInt();
                assertTrue(id<previous);assertTrue(ids.add(id));previous=id;
            }
        }
        assertEquals(21,ids.size());
        assertEquals(0,get("fixture_a","/repair-orders/page?page=4&pageSize=8",200).get("records").size());
    }
    @Test void combinationFiltersApplyToCountAndRecords() throws Exception {
        var result=get("fixture_a","/repair-orders/page?status=PENDING&deviceId=1&keyword=needle",200);
        assertEquals(1,result.get("total").asInt());assertEquals(1,result.get("records").size());
        var row=result.get("records").get(0);assertEquals(1,row.get("deviceId").asInt());assertEquals("PENDING",row.get("status").asText());
        assertEquals(2,get("fixture_admin","/repair-orders/page?status=PENDING&deviceId=1&keyword=needle",200).get("total").asInt());
    }
    @Test void adminAndForgedIdentityUseServerAuthority() throws Exception {
        assertEquals(42,get("fixture_admin","/repair-orders/page",200).get("total").asInt());
        assertEquals(28,get("fixture_admin","/repair-orders/page?status=OPEN",200).get("total").asInt());
        assertEquals(14,get("fixture_a","/repair-orders/page?status=OPEN",200).get("total").asInt());
        var result=get("fixture_a","/repair-orders/page?pageSize=100&userId=2&admin=true&role=ADMIN",200);
        assertEquals(21,result.get("total").asInt());for(var row:result.get("records"))assertEquals(1,row.get("userId").asInt());
        get(null,"/repair-orders/page",401);
    }
    @Test void searchUsesDeviceNameCodeAndLiteralText() throws Exception {
        assertEquals(9,get("fixture_a","/repair-orders/page?keyword=Pump",200).get("total").asInt());
        assertEquals(12,get("fixture_a","/repair-orders/page?keyword=QUERY-ONE",200).get("total").asInt());
        assertEquals(3,get("fixture_a","/repair-orders/page?keyword="+encode("%_"),200).get("total").asInt());
        assertEquals(0,get("fixture_a","/repair-orders/page?keyword="+encode("' OR 1=1 --"),200).get("total").asInt());
    }
    @Test void emptyResultsAndLargeOffsetAreSuccessfulPages() throws Exception {
        for(String suffix:List.of("","?status=COMPLETED","?deviceId=999999","?page=2147483647&pageSize=100")) {
            var result=get("fixture_empty","/repair-orders/page"+suffix,200);
            assertEquals(0,result.get("total").asInt());assertEquals(0,result.get("records").size());
        }
        var result=get("fixture_a","/repair-orders/page?page=2147483647&pageSize=100",200);
        assertEquals(21,result.get("total").asInt());assertEquals(0,result.get("records").size());
    }
    @Test void invalidParametersReturn400() throws Exception {
        for(String query:List.of("page=0","page=-1","page=abc","page=2147483648","pageSize=0","pageSize=101",
                "deviceId=0","deviceId=abc","status=unknown","status=pending","status=","status=%20","keyword="+"x".repeat(101)))
            get("fixture_a","/repair-orders/page?"+query,400);
    }
    @Test void legacyArrayAndDetailContractsRemainCompatible() throws Exception {
        var rows=get("fixture_a","/repair-orders?status=COMPLETED",200);assertTrue(rows.isArray());assertEquals(7,rows.size());
        int id=rows.get(0).get("id").asInt();assertEquals(id,get("fixture_a","/repair-orders/"+id,200).get("id").asInt());
        get("fixture_b","/repair-orders/"+id,403);
    }
    @AfterAll void noQueryMutatedBusinessRows() {
        assertEquals(42,jdbc.queryForObject("SELECT COUNT(*) FROM repair_order",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM reservation",Integer.class));
    }
}
