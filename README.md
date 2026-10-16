# 设备管理系统后端

基于 Spring Boot、MyBatis 和 MySQL 的设备管理 REST API，覆盖设备台账、设备预约、用户认证与维修工单四类业务。通过 JWT 识别当前用户，通过 USER / ADMIN 角色和记录归属控制访问，并对预约审批、取消及维修状态变更进行并发一致性处理。

这是后端学习与求职展示项目；当前仓库没有前端页面，也没有 Swagger / OpenAPI 页面。本文以实际源码、`pom.xml`、配置示例及数据库结构为准。交付验证详情见 [DEV-030_DELIVERY_REPORT.md](DEV-030_DELIVERY_REPORT.md)。本机已有的 `DEV-029_FULL_REGRESSION_REPORT.md` 是历史参考，不是启动依赖。

## 技术栈与结构

| 项目 | 实际配置 |
| --- | --- |
| Java | JDK 21；`pom.xml` 的 `java.version=21` |
| 后端框架 | Spring Boot 4.0.8、Spring MVC |
| 认证授权 | Spring Security、BCrypt、JJWT 0.13.0 |
| 数据访问 | MyBatis Spring Boot Starter 4.0.1、MySQL Connector/J |
| 数据库 | MySQL 8.0、InnoDB、utf8mb4；本次验证为 MySQL 8.0.46 |
| 构建 | Maven；Wrapper 3.3.4（only-script），锁定 Maven 3.10.0 |
| 测试 | JUnit Jupiter、Spring Boot Test、MockMvc、Java HttpClient、Mockito；部分测试依赖真实 MySQL |

```text
src/main/java/com/example/equipmentmanagement/
  controller/    HTTP 路由与当前身份注入
  service/       参数校验、业务规则与事务
  mapper/        MyBatis 注解 SQL
  entity/        数据实体
  dto/           注册、登录、报修请求
  config/        Spring Security 配置
  security/      JWT 认证过滤器
  util/          JWT 生成与解析
  exception/     异常及 HTTP 状态映射
  common/        分页响应
src/main/resources/application-example.properties
src/test/java/   默认测试与显式启用的集成测试
schema.sql       手动导入的纯表结构脚本
```

## 四类核心业务

| 业务 | 已实现能力及边界 |
| --- | --- |
| 设备台账 | 按 ID、状态、分页查询；管理员新增、修改、删除。分页按 ID 升序，页码从 1 开始，`pageSize` 为 1–100。新增设备状态由数据库默认设为 `AVAILABLE`；修改接口仅修改名称、位置、型号，不修改设备编码或状态。被外键引用的设备删除返回 409。 |
| 设备预约 | 当前登录用户创建预约，初始 `PENDING`；管理员审批为 `APPROVED`；本人或管理员在开始时间前可取消 `PENDING` / `APPROVED` 预约，转为 `CANCELLED`。冲突只计算同设备的 `APPROVED` 记录，条件为 `start_time < 新结束时间 AND end_time > 新开始时间`，因此首尾相接允许，多个重叠待审批申请可能共存。审批与取消使用事务、设备行锁及条件更新。 |
| 用户认证 | 注册、BCrypt 密码散列、登录签发 JWT；注册固定普通用户角色，不接受客户端指定 ADMIN。JWT subject 保存用户 ID；每次认证读取数据库中的当前用户与角色。示例过期时间为 3,600,000 毫秒（1 小时）。 |
| 维修工单 | 当前用户报修，初始 `PENDING`；管理员受理为 `PROCESSING`，再完成为 `COMPLETED`；带旧状态条件的更新防止并发重复流转。USER 列表仅本人、详情按归属校验；ADMIN 可查询全部，列表按 ID 倒序。 |

预约和工单的创建人由服务端当前身份确定，不能通过请求中的 `userId` 冒用他人。预约、工单初始状态均不由客户端决定。当前没有预约列表/详情接口、预约拒绝接口、工单取消接口、用户管理或角色提升 API。

## 权限

| 操作 | 未登录 | USER | ADMIN |
| --- | --- | --- | --- |
| 注册、登录 | 允许 | 允许 | 允许 |
| 设备查询 | 401 | 允许 | 允许 |
| 设备新增、修改、删除 | 401 | 403 | 允许 |
| 创建预约、创建报修 | 401 | 以本人身份创建 | 以本人身份创建 |
| 审批预约、受理/完成工单 | 401 | 403 | 允许 |
| 取消预约 | 401 | 仅本人，且满足时间/状态条件 | 可取消他人，但仍受时间/状态条件限制 |
| 查询工单 | 401 | 列表仅本人；他人详情 403 | 全部 |

受保护接口使用 `Authorization: Bearer <本地登录得到的JWT>`。应用使用无状态认证，禁用表单登录、HTTP Basic 和 CSRF；当前没有刷新令牌、注销/令牌黑名单功能。令牌无效、过期或对应用户不存在时返回 401。上述公开入口指无无效 Authorization 头的请求；主动携带无效 JWT 仍可能先被过滤器拒绝。

## 启动前提与安全配置（Windows PowerShell）

1. 安装 JDK 21，确认 `java -version`、`JAVA_HOME` 和命令行实际使用同一个 JDK。
2. 启动 MySQL 8.0，准备独立开发数据库。`schema.sql` 使用 `utf8mb4_0900_ai_ci`，不直接保证兼容 MySQL 5.7 或 MariaDB。
3. 首次运行 Wrapper 需要网络下载 Maven，首次构建需要下载依赖。也可使用已安装的 Maven；本次默认测试实测使用 Maven 3.9.16，Wrapper 实测使用 Maven 3.10.0。

进入项目根目录，**新检出且本地配置不存在时**复制示例；已有配置请保留，不要覆盖：

```powershell
Set-Location D:\java\equipment-management
if (!(Test-Path src\main\resources\application.properties)) {
    Copy-Item src\main\resources\application-example.properties src\main\resources\application.properties
}
```

示例通过四个环境变量取值：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`。以下在当前 PowerShell 会话设置，不把数据库口令写入命令历史或文档：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/equipment_management?serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
$dbCredential = Get-Credential -Message '输入本机开发数据库账户与密码'
$env:DB_USERNAME = $dbCredential.UserName
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password

$keyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($keyBytes) } finally { $rng.Dispose() }
$env:JWT_SECRET = [Convert]::ToBase64String($keyBytes)
```

`JWT_SECRET` 应为至少 32 个安全随机字节的 Base64 编码，不能使用短字符串或公开示例密钥。上述命令每次生成新密钥，重启后更换密钥会使旧令牌失效；需要持续登录时，应在本机安全保存并复用密钥。不要输出、截图或提交这些环境变量、登录令牌、口令、用户数据及密码散列。`.env` 仅被 Git 忽略，应用没有配置自动加载 `.env`。IDE 启动时需在对应运行配置中设置环境变量。

运行应用时使用专用数据库账户；执行全量集成测试还需要下文列出的额外数据库权限。公开部署前需自行配置 HTTPS、部署环境密钥和访问策略；本次仅验证本地开发启动。

## 数据库初始化

`schema.sql` 从实际数据库 `SHOW CREATE TABLE` 核查生成，只有四张表的结构及字符集设置，没有账户、业务行、密码散列值或其他种子数据。保留 `AUTO_INCREMENT` 列属性，去掉原库的自增计数值。脚本不包含 `DROP`、`CREATE DATABASE` 或 `USE`，也不会自动升级已有数据库；请只向新建空库导入，重复导入会因表已存在而报错。

先在 MySQL 客户端连接到本机开发实例（使用交互式口令提示，不在命令参数中写密码）。在客户端执行：

```sql
CREATE DATABASE equipment_management
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE equipment_management;
SOURCE D:/java/equipment-management/schema.sql;
SHOW TABLES;
```

PowerShell 不支持直接照搬其他 shell 的 `< schema.sql` 输入重定向；此处 `SOURCE` 是 **MySQL 客户端命令**。脚本位于仓库根目录，不会由 Spring Boot 自动初始化，需手动导入。

| 表 | 主要字段及约束 |
| --- | --- |
| `users` | 自增主键；`username varchar(50)` 唯一；`password_hash varchar(255)`；`role` 默认 `USER` |
| `device` | 自增主键；`device_code varchar(50)` 唯一；名称/型号上限 50，位置上限 100；状态默认 `AVAILABLE` |
| `reservation` | 设备/用户 ID、起止时间、状态默认 `PENDING`；**实际仅 `device_id` 有外键**，`user_id` 没有外键 |
| `repair_order` | 设备/用户外键；`description varchar(500)`；状态默认 `PENDING`；时间列为 `created_at` 与 **`update_at`**，后者自动更新时间，并由 Mapper 别名映射为 API 的 `updatedAt` |

所有表均为 InnoDB。没有额外的数据库枚举或 CHECK 状态约束，业务状态流转由服务代码和条件 SQL 实现。

空库不含管理员。先通过注册接口创建自己控制的账户，再由数据库管理员在本机开发库中将**确认过的专用账户**提升为 ADMIN，例如替换下列占位符后执行，并核对影响行数为 1：

```sql
UPDATE users SET role = 'ADMIN'
WHERE username = '<你刚注册的专用管理员用户名>' AND role = 'USER';
```

不要把共享默认管理员、口令或密码散列写入 `schema.sql`。若计划运行旧身份绑定集成测试，请保留 `id=1` 为普通 USER，并另注册第二个账户用于管理员操作。

## 启动与构建

```powershell
.\mvnw.cmd -version
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

默认端口为 8080，接口地址为 `http://127.0.0.1:8080`。访问 `/` 不提供首页，匿名访问受保护接口返回 401 属于预期。端口冲突时可在本地配置设置 `server.port`。

也可以构建并运行 JAR（默认构建会按下文默认规则运行测试）：

```powershell
.\mvnw.cmd clean package
java -jar target\equipment-management-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

应用需要的数据库变量和 JWT 密钥须在启动进程环境中可见。注意：**Git 忽略不等于 Maven 排除资源**；本地 `application.properties` 会进入构建产物。如果其中有机器专属口令或地址，不应分发该 JAR。需要分发时，从干净源码和仅含环境变量占位符的配置构建，审查产物，不直接分享本机 `target/`。

`mvnw` / `mvnw.cmd` 与 `.mvn/wrapper/maven-wrapper.properties` 已在版本管理中。only-script 类型不需要提交 `maven-wrapper.jar`。当前 Wrapper 配置包含官方发行地址与 SHA-256；本次已实际下载校验并运行 Windows Wrapper，未实际运行 Unix 脚本。

## 主要 API

请求 JSON 使用 `Content-Type: application/json`；除注册/登录外，均需 Bearer JWT。

| 方法与路径 | 权限 | 参数/请求体 | 成功响应 |
| --- | --- | --- | --- |
| `POST /users/register` | 公开 | `username`、`password` | HTTP 200，整数 `1` |
| `POST /users/login` | 公开 | `username`、`password` | HTTP 200，原始 JWT 字符串 |
| `GET /devices/{id}` | USER/ADMIN | 正整数路径 ID | 设备对象 |
| `GET /devices?status=AVAILABLE` | USER/ADMIN | 必填非空 `status` | 设备数组；未知非空状态当前返回空数组 |
| `GET /devices/page?page=1&pageSize=10` | USER/ADMIN | 必填 `page`、`pageSize` | `{total,page,pageSize,records}` |
| `POST /devices` | ADMIN | `deviceCode`、`name`、`location`，可选 `model` | 整数 `1` |
| `PUT /devices/{id}` | ADMIN | `name`、`location`，可选 `model` | 整数 `1` |
| `DELETE /devices/{id}` | ADMIN | 路径 ID | 整数 `1` |
| `POST /reservations` | USER/ADMIN | `deviceId`、`startTime`、`endTime` | 整数 `1` |
| `PUT /reservations/{id}/approve` | ADMIN | 路径预约 ID，无需请求体 | 整数 `1` |
| `PUT /reservations/{id}/cancel` | 本人/ADMIN | 路径预约 ID，无需请求体 | 整数 `1` |
| `POST /repair-orders` | USER/ADMIN | `deviceId`、`description` | 整数 `1` |
| `GET /repair-orders` | USER/ADMIN | 无 | 工单数组，按权限过滤 |
| `GET /repair-orders/{id}` | 本人/ADMIN | 路径工单 ID | 工单对象 |
| `PUT /repair-orders/{id}/process` | ADMIN | 路径工单 ID，无需请求体 | 整数 `1` |
| `PUT /repair-orders/{id}/complete` | ADMIN | 路径工单 ID，无需请求体 | 整数 `1` |

这些整数 `1` 是受影响行数，**不是新记录 ID**；没有统一的 `{code,message,data}` 响应封装。创建类接口目前也不返回 `Location`。查询设备列表/工单列表可以获得相应 ID；预约目前缺少查询 API，调试时需在自己的开发库查询新建预约 ID。

预约时间使用不带时区的本地时间格式，如 `2030-01-02T09:00:00`，要求开始时间早于结束时间。当前未实现“必须在未来创建预约”或“只允许 AVAILABLE 设备预约”的额外限制，不应据此作业务承诺。报修描述必填、非空白且服务校验 `String.length() <= 500`。

常见错误：非法参数/畸形 JSON/错误登录信息/不允许的状态变更为 400；未认证为 401；权限或归属不符为 403；设备、预约、工单不存在为 404；唯一键或外键等数据库约束冲突为 409。普通业务异常正文多为字符串，安全过滤器及框架错误响应格式可能不同。服务内部异常可能为 500，不将所有错误承诺为同一种 JSON 格式。

## 测试：默认命令与全部集成测试

在项目根目录执行。PowerShell 中将 `-D...` 参数整体加引号：

```powershell
# 默认：18 项实际执行，32 项按条件跳过
mvn test
# 使用 Wrapper 的等价入口
.\mvnw.cmd test

# 全部启用：需满足后述数据库前提
.\mvnw.cmd test '-Dfull.regression.it=true' '-Dreservation.identity.it=true' '-Dreservation.cancel.it=true'
```

| 测试类 | 方法数 | 默认执行 | 验证方式/启用开关 |
| --- | ---: | ---: | --- |
| `EquipmentManagementApplicationTests` | 1 | 1 | 应用上下文装配；不能据此声称真实数据库业务已验证 |
| `JwtAuthenticationIntegrationTest` | 14 | 14 | 嵌入式服务与真实 HTTP，数据库 Mapper 使用 mock |
| `JwtUtilTest` | 3 | 3 | JWT 单元测试 |
| `FullRegressionIntegrationTest` | 11 | 0 | 真实 HTTP + 独立 MySQL 库；`full.regression.it=true` |
| `ReservationIdentityBindingIntegrationTest` | 5 | 0 | MockMvc + 配置的真实 MySQL 库，事务回滚；`reservation.identity.it=true` |
| `ReservationCancelOwnershipIntegrationTest` | 16 | 0 | 真实 HTTP + 独立 MySQL 库；`reservation.cancel.it=true` |
| **合计** | **50** | **18** | **默认跳过 32；三开关全开才执行 50** |

本次 DEV-030 实测：`mvn test` 为 50 项发现、18 项执行通过、32 项跳过；Wrapper 全开命令为 **50 项通过、0 失败、0 错误、0 跳过**。这与历史 DEV-029 的最终报告一致，但本次另行实际复验，未直接照抄历史成绩。

全量测试前提与已知限制：

- 测试源码中两组独立库测试直接用 `Properties.load` 读取 `src/main/resources/application.properties`。它们对用户名、密码支持环境变量解析，**对 URL 不解析 `${DB_URL}`**。仅复制配置示例后，应用能启动，但这两组测试不能直接运行。运行全量前，请在 Git 忽略的本地配置中将 `spring.datasource.url` 设置成上述具体 JDBC URL；用户名、密码继续保留 `${DB_USERNAME}` / `${DB_PASSWORD}`。不要修改安全示例为个人凭据，也不要提交本地配置。
- 使用专用开发/测试 MySQL 实例；配置库中已存在上述四张 InnoDB 表。旧身份绑定测试要求 `users.id=1` 存在且 `role='USER'`，并要求 users/device/reservation 无触发器。在新空库先通过注册 API 创建第一个普通账户，不要把它提升为 ADMIN。脚本不为满足测试导出真实用户。
- 全量和取消测试创建随机名 `dev029_*`、`dev027_*` 数据库，只复制结构并插入临时夹具。数据库账户需有建库、建表、相应 DML 权限；并发回归还需读取 `performance_schema.data_lock_waits`、`performance_schema.threads`。这些是测试权限，不是普通应用运行所必需的权限。
- 身份绑定测试在配置库内插入后回滚，正常运行不保留测试行，但 MySQL 自增计数可能前进。独立库测试正常结束清空自己的夹具，保留库和空表；外部强制终止可能遗留夹具，应按明确测试库名检查，不要清空业务库。
- 取消测试有一个故障注入用例，预期收到 HTTP 500 并检查事务回滚。应以 Surefire 最终失败/错误数判断结果，不将预期日志堆栈误判为回归失败。

项目没有配置专门启用这些开关的 Maven profile 或 Failsafe 流程。`mvn verify` 不会自动把条件测试全部启用，`-DskipTests` 会跳过测试。原报告中的 `-o` 表示离线解析 Maven 依赖，只在相关依赖/插件已缓存时可用；它不是集成测试开关，也不阻止未缓存 Wrapper 自行下载 Maven。

只运行无需数据库配置的认证/JWT测试可使用：

```powershell
.\mvnw.cmd test '-Dtest=JwtAuthenticationIntegrationTest,JwtUtilTest'
```

Surefire 结果位于 `target/surefire-reports/`，后一次运行会刷新同名报告。查看本次交付报告时应区分默认与全开两次执行，不能把默认 BUILD SUCCESS 等同于 50 项全部执行。

## Git 与交付边界

已核查 `.gitignore` 忽略本地 `application.properties`、`.env`、本地配置、IDE 文件、`target/`、日志、测试运行态和生成报告；安全的 `application-example.properties` 与 Wrapper 文件保留在版本管理中。新增 `README.md`、`schema.sql`、`DEV-030_DELIVERY_REPORT.md` 可纳入版本管理。

当前还有此前任务留下的未跟踪报告和 `verification/` 材料；不要未经逐项审查就 `git add .`。本次没有执行提交、推送或公开仓库操作；待用户确认具体文件后再处理。交付后的系统学习与面试训练不属于本次代码/文档整理范围。
