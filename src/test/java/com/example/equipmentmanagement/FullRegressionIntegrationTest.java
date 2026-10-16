package com.example.equipmentmanagement;

import com.example.equipmentmanagement.util.JwtUtil;
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
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP / MySQL, structures only in a unique isolated schema. No business-row writes. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "logging.level.root=OFF", "spring.main.banner-mode=off", "server.address=127.0.0.1",
 "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnabledIfSystemProperty(named="full.regression.it", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FullRegressionIntegrationTest {
 static final String KEY=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
 static final String SCHEMA="dev029_"+UUID.randomUUID().toString().replace("-", "");
 static final List<String> TABLES=List.of("users","device","reservation","repair_order");
 static final Map<String,String> BEFORE=new LinkedHashMap<>();
 static final List<String> EVIDENCE=Collections.synchronizedList(new ArrayList<>());
 static String sourceUrl,targetUrl,dbUser,dbPassword;
 static final JsonMapper JSON=JsonMapper.builder().build();
 @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of("src/main/resources/application.properties"))){p.load(in);}
  sourceUrl=p.getProperty("spring.datasource.url");dbUser=resolve(p.getProperty("spring.datasource.username"));dbPassword=resolve(p.getProperty("spring.datasource.password"));
  String source=sourceUrl.substring(sourceUrl.lastIndexOf('/')+1).split("\\?")[0];
  if(!source.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Unsupported schema");
  targetUrl=sourceUrl.substring(0,sourceUrl.lastIndexOf('/')+1)+SCHEMA+(sourceUrl.contains("?")?sourceUrl.substring(sourceUrl.indexOf('?')):"");
  try(var c=DriverManager.getConnection(sourceUrl,dbUser,dbPassword);var s=c.createStatement()){
   for(String t:TABLES){
    try(var rs=s.executeQuery("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+t+"'")){assertTrue(rs.next());assertEquals("InnoDB",rs.getString(1));}
    BEFORE.put(t,fingerprint(c,t));
   }
   s.executeUpdate("CREATE DATABASE `"+SCHEMA+"`");c.setCatalog(SCHEMA);
   for(String t:TABLES){String ddl;try(var rs=s.executeQuery("SHOW CREATE TABLE `"+source+"`.`"+t+"`")){rs.next();ddl=rs.getString(2);}
    ddl=ddl.replace("CREATE TABLE `"+t+"`","CREATE TABLE `"+SCHEMA+"`.`"+t+"`").replace("`"+source+"`.","`"+SCHEMA+"`.").replaceAll("AUTO_INCREMENT=\\d+","AUTO_INCREMENT=1");s.executeUpdate(ddl);
   }
  }
  evidence("ISOLATED_SCHEMA "+SCHEMA+" structures only; source="+source+" fingerprints="+BEFORE);
  r.add("spring.datasource.url",()->targetUrl);r.add("app.jwt.secret",()->KEY);
 }
 static String resolve(String v){if(v.startsWith("${")&&v.endsWith("}")){String x=System.getenv(v.substring(2,v.length()-1));if(x==null)throw new IllegalStateException("DB environment missing");return x;}return v;}
 static String fingerprint(Connection c,String t)throws Exception {
  MessageDigest d=MessageDigest.getInstance("SHA-256");int n=0;
  try(var s=c.createStatement();var rs=s.executeQuery("SELECT * FROM "+t+" ORDER BY id")){while(rs.next()){n++;for(int i=1;i<=rs.getMetaData().getColumnCount();i++){String v=rs.getString(i);d.update((v==null?"N;":"S"+v.length()+":"+v+";").getBytes(StandardCharsets.UTF_8));}}}
  return n+":"+HexFormat.of().formatHex(d.digest());
 }
 static void evidence(String s){EVIDENCE.add(s);System.out.println("DEV029 "+s);}
 @LocalServerPort int port; @Autowired JdbcTemplate jdbc; @Autowired JwtUtil jwt;
 HttpClient client; int a,b,admin,admin2,device;String ta,tb,tx,ty;int serial;
 final LocalDateTime future=LocalDateTime.now().plusDays(40).withNano(0);
 @BeforeAll void fixtures()throws Exception {
  client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  a=account("a",false);b=account("b",false);admin=account("admin",true);admin2=account("admin2",true);
  ta=tokens.get(a);tb=tokens.get(b);tx=tokens.get(admin);ty=tokens.get(admin2);device=newDevice();
  evidence("SERVER 127.0.0.1:"+port+" Asia/Shanghai="+ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
 }
 final Map<Integer,String> tokens=new HashMap<>();
 int account(String name,boolean isAdmin)throws Exception {
  String password=UUID.randomUUID().toString();String body=JSON.writeValueAsString(Map.of("username",name,"password",password,"role","ADMIN"));
  expect("register "+name,"POST","/users/register",null,body,200);
  int id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Integer.class,name);
  assertEquals("USER",jdbc.queryForObject("SELECT role FROM users WHERE id=?",String.class,id));
  String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id);
  assertTrue(hash!=null&&!hash.equals(password)&&new BCryptPasswordEncoder().matches(password,hash));
  if(isAdmin)assertEquals(1,jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",id));
  var login=expect("login "+name,"POST","/users/login",null,body,200);assertEquals(id,jwt.parseToken(login.body()));tokens.put(id,login.body());
  evidence("BCrypt + registration role protected + JWT subject verified account="+name);return id;
 }
 HttpResponse<String> call(String method,String path,String token,String body)throws Exception {
  var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
  if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.header("Content-Type","application/json");
  return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 HttpResponse<String> expect(String label,String method,String path,String token,String body,int expected)throws Exception {
  var response=call(method,path,token,body);evidence(label+" "+method+" "+path+" expected="+expected+" actual="+response.statusCode());
  assertEquals(expected,response.statusCode(),label+" (body/credentials omitted)");if(expected==200&&!method.equals("GET")&&!path.endsWith("login"))assertEquals("1",response.body());return response;
 }
 String deviceJson(String code){return JSON.writeValueAsString(Map.of("deviceCode",code,"name","Device","location","Lab","model","M"));}
 int newDevice()throws Exception {String code="test-"+(++serial);expect("device create","POST","/devices",tx==null?tokens.get(admin):tx,deviceJson(code),200);return jdbc.queryForObject("SELECT id FROM device WHERE device_code=?",Integer.class,code);}
 String reservationJson(int d,LocalDateTime s,LocalDateTime e){return JSON.writeValueAsString(Map.of("deviceId",d,"userId",b,"status","APPROVED","startTime",s.toString(),"endTime",e.toString()));}
 int reservation(int d,LocalDateTime s,LocalDateTime e,String token)throws Exception {
  expect("reservation create","POST","/reservations",token,reservationJson(d,s,e),200);
  int id=jdbc.queryForObject("SELECT MAX(id) FROM reservation WHERE device_id=?",Integer.class,d);
  assertEquals("PENDING",state("reservation",id));assertEquals(jwt.parseToken(token),jdbc.queryForObject("SELECT user_id FROM reservation WHERE id=?",Integer.class,id));return id;
 }
 String state(String table,int id){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id);}
 int order(String token)throws Exception {
  String desc="failure-"+(++serial);expect("repair create","POST","/repair-orders",token,JSON.writeValueAsString(Map.of("deviceId",device,"description",desc,"userId",b,"status","COMPLETED")),200);
  int id=jdbc.queryForObject("SELECT id FROM repair_order WHERE description=?",Integer.class,desc);assertEquals("PENDING",state("repair_order",id));assertEquals(jwt.parseToken(token),jdbc.queryForObject("SELECT user_id FROM repair_order WHERE id=?",Integer.class,id));return id;
 }
 @Test @Order(1) void authentication()throws Exception {
  expect("duplicate registration","POST","/users/register",null,"{\"username\":\"a\",\"password\":\"temporary\"}",400);
  for(String body:List.of("{}","{\"username\":\" \"}","{\"username\":\"none\",\"password\":\"wrong\"}","{\"username\":\"a\",\"password\":\"wrong\"}"))expect("invalid login","POST","/users/login",null,body,400);
  for(String token:Arrays.asList(null,"","invalid",new JwtUtil(KEY,-60000).generateToken(a),jwt.generateToken(2147483647))){expect("unauthorized JWT","GET","/devices?status=AVAILABLE",token,null,401);}
  var otherKey=Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());expect("wrong signature","GET","/devices?status=AVAILABLE",new JwtUtil(otherKey,60000).generateToken(a),null,401);
  expect("valid JWT","GET","/devices?status=AVAILABLE",ta,null,200);expect("context cleared after JWT","GET","/devices?status=AVAILABLE",null,null,401);
  for(String path:List.of("/devices","/reservations","/repair-orders"))expect("anonymous write","POST",path,null,"{}",401);
  for(String path:List.of("/devices/1","/reservations/1/approve","/repair-orders/1/process","/repair-orders/1/complete"))expect("USER forbidden write","PUT",path,ta,"{}",403);
  expect("USER forbidden create device","POST","/devices",ta,deviceJson("denied"),403);expect("USER forbidden delete","DELETE","/devices/1",ta,null,403);
 }
 @Test @Order(2) void deviceCrud()throws Exception {
  int id=newDevice();expect("device detail","GET","/devices/"+id,ta,null,200);
  expect("device update","PUT","/devices/"+id,tx,"{\"name\":\"Changed\",\"location\":\"Room\",\"model\":\"N\"}",200);
  var detail=JSON.readTree(expect("updated device","GET","/devices/"+id,ta,null,200).body());assertEquals("Changed",detail.get("name").asText());assertEquals("AVAILABLE",detail.get("status").asText());
  expect("duplicate code","POST","/devices",tx,deviceJson("test-"+serial),409);
  expect("invalid device body","POST","/devices",tx,"{}",400);expect("invalid update body","PUT","/devices/"+id,tx,"{}",400);
  expect("missing device","GET","/devices/2147483647",ta,null,404);
  for(String bad:List.of("0","-1","abc","2147483648"))expect("invalid device ID","GET","/devices/"+bad,ta,null,400);
  expect("delete device","DELETE","/devices/"+id,tx,null,200);expect("deleted detail","GET","/devices/"+id,ta,null,404);expect("repeat delete","DELETE","/devices/"+id,tx,null,404);
  jdbc.update("UPDATE device SET status='MAINTENANCE' WHERE id=?",device);
  var status=JSON.readTree(expect("status filter","GET","/devices?status=MAINTENANCE",ta,null,200).body());assertEquals(1,status.size());assertEquals(device,status.get(0).get("id").asInt());
  expect("empty status","GET","/devices?status=",ta,null,400);expect("missing status","GET","/devices",ta,null,400);expect("unknown status returns empty","GET","/devices?status=UNKNOWN",ta,null,200);
  jdbc.update("UPDATE device SET status='AVAILABLE' WHERE id=?",device);
 }
 @Test @Order(3) void pagination()throws Exception {
  for(int i=0;i<3;i++)newDevice();int total=jdbc.queryForObject("SELECT COUNT(*) FROM device",Integer.class);
  var first=JSON.readTree(expect("page one","GET","/devices/page?page=1&pageSize=2",ta,null,200).body());var second=JSON.readTree(expect("page two","GET","/devices/page?page=2&pageSize=2",ta,null,200).body());
  assertEquals(total,first.get("total").asInt());assertEquals(2,first.get("records").size());assertTrue(first.get("records").get(1).get("id").asInt()<second.get("records").get(0).get("id").asInt());
  for(String q:List.of("page=0&pageSize=1","page=1&pageSize=0","page=-1&pageSize=1","page=1&pageSize=101","page=abc&pageSize=2","page=2147483648&pageSize=2","page=1"))expect("invalid page","GET","/devices/page?"+q,ta,null,400);
  var huge=JSON.readTree(expect("large page no overflow","GET","/devices/page?page=2147483647&pageSize=100",ta,null,200).body());assertEquals(0,huge.get("records").size());
 }
 @Test @Order(4) void reservations()throws Exception {
  int d=newDevice();int id=reservation(d,future,future.plusHours(1),ta);
  expect("other owner cancel","PUT","/reservations/"+id+"/cancel",tb,null,403);assertEquals("PENDING",state("reservation",id));
  int overlap=reservation(d,future.plusMinutes(10),future.plusHours(2),tb);
  expect("approve","PUT","/reservations/"+id+"/approve",tx,null,200);assertEquals("APPROVED",state("reservation",id));
  expect("repeat approve","PUT","/reservations/"+id+"/approve",ty,null,400);expect("approve overlap","PUT","/reservations/"+overlap+"/approve",tx,null,400);
  for(LocalDateTime[] t:List.of(new LocalDateTime[]{future,future.plusHours(1)},new LocalDateTime[]{future.minusHours(1),future.plusHours(2)},new LocalDateTime[]{future.plusMinutes(20),future.plusMinutes(30)}))expect("approved overlap create","POST","/reservations",ta,reservationJson(d,t[0],t[1]),400);
  int adjacent=reservation(d,future.plusHours(1),future.plusHours(2),tb);expect("adjacent approve","PUT","/reservations/"+adjacent+"/approve",tx,null,200);
  expect("owner cancel approved","PUT","/reservations/"+id+"/cancel",ta,null,200);expect("repeat cancel","PUT","/reservations/"+id+"/cancel",ta,null,400);
  expect("admin cancel others","PUT","/reservations/"+overlap+"/cancel",tx,null,200);expect("cancelled approve denied","PUT","/reservations/"+overlap+"/approve",tx,null,400);
  int pending=reservation(d,future.plusHours(3),future.plusHours(4),ta);expect("owner pending cancel","PUT","/reservations/"+pending+"/cancel",ta,null,200);
  int started=reservation(d,future.plusHours(5),future.plusHours(6),ta);jdbc.update("UPDATE reservation SET start_time=?,end_time=? WHERE id=?",LocalDateTime.now().minusHours(1),LocalDateTime.now().plusHours(1),started);expect("started cannot cancel","PUT","/reservations/"+started+"/cancel",ta,null,400);
  for(String body:List.of("{}",reservationJson(d,future,future),reservationJson(d,future.plusHours(1),future),reservationJson(2147483647,future,future.plusHours(1))))expect("invalid reservation","POST","/reservations",ta,body,body.contains("2147483647")?404:400);
  for(String action:List.of("approve","cancel"))expect("missing reservation","PUT","/reservations/2147483647/"+action,tx,null,404);
 }
 @Test @Order(5) void repairOrders()throws Exception {
  int own=order(ta),other=order(tb);
  var list=JSON.readTree(expect("USER own orders","GET","/repair-orders",ta,null,200).body());assertEquals(1,list.size());assertEquals(own,list.get(0).get("id").asInt());
  var all=JSON.readTree(expect("ADMIN all orders","GET","/repair-orders",tx,null,200).body());assertEquals(2,all.size());assertEquals(other,all.get(0).get("id").asInt());
  expect("USER other detail","GET","/repair-orders/"+other,ta,null,403);expect("own detail","GET","/repair-orders/"+own,ta,null,200);expect("ADMIN other detail","GET","/repair-orders/"+other,tx,null,200);
  expect("pending cannot complete","PUT","/repair-orders/"+own+"/complete",tx,null,400);expect("process","PUT","/repair-orders/"+own+"/process",tx,null,200);assertEquals("PROCESSING",state("repair_order",own));
  expect("repeat process","PUT","/repair-orders/"+own+"/process",ty,null,400);expect("complete","PUT","/repair-orders/"+own+"/complete",ty,null,200);assertEquals("COMPLETED",state("repair_order",own));
  expect("repeat complete","PUT","/repair-orders/"+own+"/complete",tx,null,400);expect("completed cannot process","PUT","/repair-orders/"+own+"/process",tx,null,400);
  var result=JSON.readTree(expect("mapped timestamps","GET","/repair-orders/"+own,ta,null,200).body());assertNotNull(result.get("createdAt"));assertEquals(jdbc.queryForObject("SELECT update_at FROM repair_order WHERE id=?",LocalDateTime.class,own),LocalDateTime.parse(result.get("updatedAt").asText()));
  for(String body:List.of("{}","{\"deviceId\":0,\"description\":\"x\"}",JSON.writeValueAsString(Map.of("deviceId",device,"description"," ")),JSON.writeValueAsString(Map.of("deviceId",device,"description","x".repeat(501)))))expect("invalid repair","POST","/repair-orders",ta,body,400);
  expect("missing repair device","POST","/repair-orders",ta,"{\"deviceId\":2147483647,\"description\":\"x\"}",404);
  for(String path:List.of("/repair-orders/2147483647","/repair-orders/0","/repair-orders/abc"))expect("invalid repair detail","GET",path,ta,null,path.endsWith("2147483647")?404:400);
  for(String action:List.of("process","complete")){for(String bad:List.of("0","-1","abc","2147483648"))expect("invalid transition ID","PUT","/repair-orders/"+bad+"/"+action,tx,null,400);expect("missing transition","PUT","/repair-orders/2147483647/"+action,tx,null,404);}
 }
 @Test @Order(6) void concurrentApprovals()throws Exception {
  for(int round=1;round<=10;round++){
   int d=newDevice();int first=reservation(d,future,future.plusHours(1),ta),second=reservation(d,future.plusMinutes(10),future.plusHours(2),tb);
   race("approve overlapping round="+round,"device",d,"/reservations/"+first+"/approve","/reservations/"+second+"/approve");
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE device_id=? AND status='APPROVED'",Integer.class,d));
  }
  int d=newDevice(),id=reservation(d,future,future.plusHours(1),ta);race("same reservation","device",d,"/reservations/"+id+"/approve","/reservations/"+id+"/approve");assertEquals("APPROVED",state("reservation",id));
 }
 @Test @Order(7) void concurrentRepairTransitions()throws Exception {
  for(int round=1;round<=10;round++){int id=order(ta);for(String action:List.of("process","complete")){race("repair "+action+" round="+round,"repair_order",id,"/repair-orders/"+id+"/"+action,"/repair-orders/"+id+"/"+action);assertEquals(action.equals("process")?"PROCESSING":"COMPLETED",state("repair_order",id));}}
 }
 void race(String label,String table,int id,String p1,String p2)throws Exception {
  try(var lock=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var pool=Executors.newFixedThreadPool(2)){
   lock.setAutoCommit(false);long connection;try(var s=lock.createStatement();var rs=s.executeQuery("SELECT CONNECTION_ID()")){rs.next();connection=rs.getLong(1);}
   try(var s=lock.prepareStatement("SELECT id FROM "+table+" WHERE id=? FOR UPDATE")){s.setInt(1,id);try(var rs=s.executeQuery()){assertTrue(rs.next());}}
   CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);var x=pool.submit(()->{ready.countDown();start.await();return call("PUT",p1,tx,null);});var y=pool.submit(()->{ready.countDown();start.await();return call("PUT",p2,ty,null);});
   try{assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);int waiting=0;
    while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits WHERE BLOCKING_THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=?)",Integer.class,connection);if(waiting==2||x.isDone()||y.isDone())break;Thread.sleep(20);}assertEquals(2,waiting,"Both real transactions must wait");
   }finally{lock.rollback();}
   var rx=x.get(20,TimeUnit.SECONDS);var ry=y.get(20,TimeUnit.SECONDS);evidence("CONCURRENT "+label+" observed 2 MySQL waiters HTTP="+rx.statusCode()+","+ry.statusCode());
   assertEquals(List.of(200,400),java.util.stream.Stream.of(rx.statusCode(),ry.statusCode()).sorted().toList());assertEquals("1",rx.statusCode()==200?rx.body():ry.body());
  }
 }
 @Test @Order(8) void fieldLengthsAndForeignKeyErrors()throws Exception {
  List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
  for(String field:List.of("deviceCode","name","location","model")){
   Map<String,Object> body=new HashMap<>(Map.of("deviceCode","length-"+field,"name","Device","location","Lab","model","M"));body.put(field,"x".repeat(field.equals("location")?101:51));
   checks.add(()->expect("overlong device "+field,"POST","/devices",tx,JSON.writeValueAsString(body),400));
   if(!field.equals("deviceCode")) checks.add(()->expect("overlong update "+field,"PUT","/devices/"+device,tx,JSON.writeValueAsString(body),400));
  }
  checks.add(()->expect("overlong username","POST","/users/register",null,JSON.writeValueAsString(Map.of("username","x".repeat(51),"password",UUID.randomUUID().toString())),400));
  assertAll(checks);
 }
 @Test @Order(9) void foreignKeyConflict()throws Exception {
  int referenced=newDevice();reservation(referenced,future,future.plusHours(1),ta);
  var r=expect("delete referenced device conflict","DELETE","/devices/"+referenced,tx,null,409);evidence("FK conflict response="+r.body());assertFalse(r.body().contains("已存在"),"FK conflict must not claim duplicate device code");
 }
 @Test @Order(10) void concurrentRegistration()throws Exception {
  String name="concurrent-user",password=UUID.randomUUID().toString();String body=JSON.writeValueAsString(Map.of("username",name,"password",password));
  try(var lock=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var pool=Executors.newFixedThreadPool(2)){
   lock.setAutoCommit(false);long connection;try(var s=lock.createStatement();var rs=s.executeQuery("SELECT CONNECTION_ID()")){rs.next();connection=rs.getLong(1);}
   // Unique-index gap lock: both requests finish their not-found precheck before INSERT waits.
   try(var s=lock.prepareStatement("SELECT id FROM users WHERE username=? FOR UPDATE")){s.setString(1,name);try(var rs=s.executeQuery()){assertFalse(rs.next());}}
   CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);var x=pool.submit(()->{ready.countDown();start.await();return call("POST","/users/register",null,body);});var y=pool.submit(()->{ready.countDown();start.await();return call("POST","/users/register",null,body);});
   try{assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);int waiting=0;
    while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits WHERE BLOCKING_THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=?)",Integer.class,connection);if(waiting==2||x.isDone()||y.isDone())break;Thread.sleep(20);}assertEquals(2,waiting);
   }finally{lock.rollback();}
   var rx=x.get(20,TimeUnit.SECONDS);var ry=y.get(20,TimeUnit.SECONDS);evidence("CONCURRENT registration 2 waiters HTTP="+rx.statusCode()+","+ry.statusCode());assertEquals(List.of(200,400),java.util.stream.Stream.of(rx.statusCode(),ry.statusCode()).sorted().toList());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,name));
  }
 }
 @Test @Order(11) void acceptedLengthBoundariesAndMalformedRequests()throws Exception {
  String code="C".repeat(50);String valid=JSON.writeValueAsString(Map.of("deviceCode",code,"name","名".repeat(50),"location","地".repeat(100),"model","M".repeat(50)));
  expect("device max lengths accepted","POST","/devices",tx,valid,200);
  int id=jdbc.queryForObject("SELECT id FROM device WHERE device_code=?",Integer.class,code);
  expect("device update max lengths accepted","PUT","/devices/"+id,tx,valid,200);
  assertEquals("名".repeat(50),jdbc.queryForObject("SELECT name FROM device WHERE id=?",String.class,id));
  expect("registration max username length accepted","POST","/users/register",null,JSON.writeValueAsString(Map.of("username","U".repeat(50),"password",UUID.randomUUID().toString())),200);
  for(String path:List.of("/devices","/reservations","/repair-orders","/users/register","/users/login")) {
   String token=path.equals("/devices")?tx:ta;
   expect("malformed JSON","POST",path,token,"{",400);expect("JSON null body","POST",path,token,"null",400);
  }
  int repair=order(ta);
  expect("current DB role governs existing JWT","PUT","/repair-orders/"+repair+"/process",ty,null,200);
  jdbc.update("UPDATE users SET role='USER' WHERE id=?",admin2);
  try{expect("role demotion effective on existing JWT","PUT","/repair-orders/"+repair+"/complete",ty,null,403);}finally{jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",admin2);}
  expect("role restored effective on existing JWT","PUT","/repair-orders/"+repair+"/complete",ty,null,200);
 }
 @AfterAll void cleanupAndVerify()throws Exception {
  try{
   // Delete only known tables in this generated schema. The database is retained empty for inspection.
   assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
   for(String t:List.of("repair_order","reservation","device","users"))jdbc.update("DELETE FROM "+t);
   for(String t:TABLES)assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Integer.class));
   try(var c=DriverManager.getConnection(sourceUrl,dbUser,dbPassword)){for(String t:TABLES)assertEquals(BEFORE.get(t),fingerprint(c,t),"Source rows unchanged: "+t);}
   evidence("CLEANUP isolated schema empty; source four table fingerprints unchanged="+BEFORE);
  }finally{if(client!=null)client.close();Files.createDirectories(Path.of("verification/dev029"));Files.write(Path.of("verification/dev029/latest-evidence.txt"),EVIDENCE,StandardCharsets.UTF_8);}
 }
}
