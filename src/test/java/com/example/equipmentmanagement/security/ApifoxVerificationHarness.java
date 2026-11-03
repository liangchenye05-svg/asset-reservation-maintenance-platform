package com.example.equipmentmanagement.security;

import com.example.equipmentmanagement.EquipmentManagementApplication;
import com.example.equipmentmanagement.testsupport.IsolatedDatabaseTest;
import com.example.equipmentmanagement.util.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** Local Apifox CLI harness: no mock, no production changes, own MySQL schema only. */
public class ApifoxVerificationHarness {
    private static final Path DIR=Path.of("verification/apifox");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final List<String> EVIDENCE=Collections.synchronizedList(new ArrayList<>());
    private static final List<Integer> IDS=new ArrayList<>();
    private static void log(String s) { EVIDENCE.add(s); System.out.println(s); }
    private static int insert(JdbcTemplate jdbc,String sql,Object... args) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);
        return key.getKey().intValue();
    }
    private static Map<String,Object> row(JdbcTemplate jdbc,int id) {
        var raw=jdbc.queryForMap("SELECT id,user_id,device_id,status,start_time,end_time FROM reservation WHERE id=?",id);
        Map<String,Object> r=new LinkedHashMap<>();raw.forEach((k,v)->r.put(k,v instanceof Number?v:v.toString()));return r;
    }
    public static void main(String[] args) throws Exception {
        if(!Boolean.getBoolean("apifox.harness.it")) throw new IllegalStateException("Explicit apifox.harness.it=true opt-in required");
        Map<String,Object> settings=new HashMap<>();
        ReservationCancelOwnershipIntegrationTest.isolatedDatabase((k,v)->settings.put(k,v.get()));
        Files.createDirectories(DIR);
        settings.put("server.port","0");settings.put("server.address","127.0.0.1");settings.put("logging.level.root","ERROR");settings.put("spring.main.banner-mode","off");
        settings.put("spring.autoconfigure.exclude","org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
        settings.forEach((k,v)->System.setProperty(k,v.toString()));
        HttpServer observer=null; ExecutorService observerThreads=Executors.newFixedThreadPool(2);
        int cliExit=1; List<String> tokens=new ArrayList<>(); Path runtime=DIR.resolve("runtime-local.json");
        var application=new SpringApplication(EquipmentManagementApplication.class);
        application.setAdditionalProfiles("isolated-test");
        application.addInitializers(new IsolatedDatabaseTest.Initializer());
        try(var ctx=application.run()) {
            JdbcTemplate jdbc=ctx.getBean(JdbcTemplate.class);JwtUtil jwt=ctx.getBean(JwtUtil.class);
            String schema=jdbc.queryForObject("SELECT DATABASE()",String.class);
            if(!schema.equals(ReservationCancelOwnershipIntegrationTest.targetDatabase.schema()))throw new IllegalStateException("Must use independent test schema");
            String pass=UUID.randomUUID().toString(),hash=new BCryptPasswordEncoder().encode(pass);
            int a=insert(jdbc,"INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","apifox-a",hash,"USER");
            int b=insert(jdbc,"INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","apifox-b",hash,"USER");
            int admin=insert(jdbc,"INSERT INTO users(username,password_hash,role) VALUES (?,?,?)","apifox-admin",hash,"ADMIN");
            int device=insert(jdbc,"INSERT INTO device(device_code,name,location,model) VALUES (?,?,?,?)","apifox-dev027","Apifox DEV-027","TEST","TEST");
            LocalDateTime future=LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS),started=LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);
            String[] statuses={"PENDING","PENDING","APPROVED","APPROVED","APPROVED","PENDING","APPROVED","CANCELLED","PENDING"};
            for(int i=0;i<statuses.length;i++) {
                LocalDateTime start=(i==5||i==6)?started:future;
                int id=insert(jdbc,"INSERT INTO reservation(device_id,user_id,start_time,end_time,status) VALUES (?,?,?,?,?)",device,a,start,start.plusHours(2),statuses[i]);
                if(id!=i+1)throw new IllegalStateException("Unexpected independent fixture ID");IDS.add(id);
            }
            String base="http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port");
            try(HttpClient client=HttpClient.newHttpClient()) {
                for(var entry:List.of(Map.entry("apifox-a",a),Map.entry("apifox-b",b),Map.entry("apifox-admin",admin))) {
                    String body=JSON.writeValueAsString(Map.of("username",entry.getKey(),"password",pass));
                    var response=client.send(HttpRequest.newBuilder(URI.create(base+"/users/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()!=200||!jwt.parseToken(response.body()).equals(entry.getValue()))throw new IllegalStateException("Fixture login failed; credentials omitted");tokens.add(response.body());
                }
            }
            observer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);observer.setExecutor(observerThreads);
            observer.createContext("/snapshot",exchange->{
                try {
                    String query=exchange.getRequestURI().getQuery();int id=Integer.parseInt(query.substring("id=".length()));
                    if(!IDS.contains(id)||!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(404,-1);return; }
                    Map<String,Object> snapshot=row(jdbc,id);String json=JSON.writeValueAsString(snapshot);
                    log("SQL_SNAPSHOT id="+id+" "+json);
                    byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                } catch(Exception e) { exchange.sendResponseHeaders(500,-1); } finally {exchange.close();}
            });observer.start();
            String observerUrl="http://127.0.0.1:"+observer.getAddress().getPort();
            log("APIFOX_ENV schema="+schema+" baseUrl="+base+" observer="+observerUrl+" USER_A="+a+" USER_B="+b+" ADMIN="+admin+" DEVICE="+device+" future="+future+" started="+started);
            for(int id:IDS)log("INITIAL "+JSON.writeValueAsString(row(jdbc,id)));
            String template=Files.readString(DIR.resolve("collection-template.json"),StandardCharsets.UTF_8);
            String collection=template.replace("__BASE_URL__",base).replace("__OBSERVER_URL__",observerUrl).replace("__TOKEN_A__",tokens.get(0)).replace("__TOKEN_B__",tokens.get(1)).replace("__TOKEN_ADMIN__",tokens.get(2));
            Files.writeString(runtime,collection,StandardCharsets.UTF_8);
            Process cli=new ProcessBuilder("node",DIR.resolve("cli/node_modules/apifox-cli/bin/cli.js").toString(),"run",runtime.toString(),"-r","cli,json,html,junit","--out-dir",DIR.resolve("reports").toString(),"--out-file","dev027-apifox","--color","off","--timeout-request","10000","--timeout-script","10000").redirectErrorStream(true).redirectOutput(DIR.resolve("cli-raw.log").toFile()).start();
            if(!cli.waitFor(90,TimeUnit.SECONDS)){cli.destroyForcibly();throw new IllegalStateException("Apifox CLI timeout");}cliExit=cli.exitValue();
            log("APIFOX_CLI_EXIT "+cliExit);
            for(int id:IDS)log("FINAL "+JSON.writeValueAsString(row(jdbc,id)));
            String expected[]={"CANCELLED","CANCELLED","CANCELLED","CANCELLED","APPROVED","PENDING","APPROVED","CANCELLED","PENDING"};
            for(int i=0;i<IDS.size();i++)if(!expected[i].equals(row(jdbc,IDS.get(i)).get("status")))throw new IllegalStateException("Final SQL state mismatch at fixture "+IDS.get(i));
            var sourceField=ReservationCancelOwnershipIntegrationTest.class.getDeclaredField("ORIGINAL");sourceField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,String> original=(Map<String,String>)sourceField.get(null);
            var method=ReservationCancelOwnershipIntegrationTest.class.getDeclaredMethod("fingerprint",Connection.class,String.class);method.setAccessible(true);
            try(Connection source=ReservationCancelOwnershipIntegrationTest.sourceDatabase.open()) {
                for(var entry:original.entrySet()) {
                    String after=(String)method.invoke(null,source,entry.getKey());if(!entry.getValue().equals(after))throw new IllegalStateException("Source data changed: "+entry.getKey());
                    log("SOURCE_UNCHANGED table="+entry.getKey()+" beforeRows="+entry.getValue().split(":")[0]+" afterRows="+after.split(":")[0]+" fingerprintMatch=true");
                }
            }
            String sql="USE "+schema+";\nSELECT id,user_id,device_id,status,start_time,end_time FROM reservation ORDER BY id;\n";
            Files.writeString(DIR.resolve("verify.sql"),sql,StandardCharsets.UTF_8);
        } finally {
            if(observer!=null)observer.stop(0);observerThreads.shutdownNow();
            if(Files.exists(runtime)) Files.writeString(runtime,"{\"notice\":\"Temporary local JWT values removed after Apifox run. Re-run harness to regenerate.\"}",StandardCharsets.UTF_8);
            // Redact all temporary JWTs before any reports are shown or delivered.
            try(var paths=Files.walk(DIR)) {
                for(Path file:paths.filter(Files::isRegularFile).filter(p->!p.toString().contains("node_modules")&&!p.toString().contains("\\store\\")).filter(p->p.startsWith(DIR.resolve("reports"))||p.equals(DIR.resolve("cli-raw.log"))).toList()) {
                    String text=Files.readString(file,StandardCharsets.UTF_8);for(String token:tokens)text=text.replace(token,"<redacted-test-jwt>");Files.writeString(file,text,StandardCharsets.UTF_8);
                }
            }
            String exported=Files.readString(DIR.resolve("collection-template.json"),StandardCharsets.UTF_8).replace("__BASE_URL__", "http://127.0.0.1:YOUR_TEST_PORT").replace("__OBSERVER_URL__","http://127.0.0.1:YOUR_OBSERVER_PORT").replace("__TOKEN_A__","").replace("__TOKEN_B__","").replace("__TOKEN_ADMIN__","");
            Files.writeString(DIR.resolve("DEV027.postman_collection.json"),exported,StandardCharsets.UTF_8);
            Files.write(DIR.resolve("database-evidence.txt"),EVIDENCE,StandardCharsets.UTF_8);
        }
        if(cliExit!=0)throw new IllegalStateException("Apifox CLI reported failed assertions; inspect redacted local reports");
    }
}
