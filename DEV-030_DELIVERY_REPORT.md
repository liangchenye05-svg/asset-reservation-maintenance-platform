# DEV-030 交付整理报告

- 项目：`D:\java\equipment-management`
- 核查与交付日期：2026-10-16，Asia/Shanghai。
- 范围：依据实际源码、POM、配置示例、数据库结构及历史测试报告整理交付文档；不新增业务功能。
- 结论：README 与纯结构脚本已整理；默认测试及全部集成测试已分别复验；Windows Wrapper、官方发行包校验、空库导入、配置示例启动及 JAR 构建验证通过。下文明确保留测试可移植性和分发配置限制。
- Git：未执行 `git add`、`commit`、`push`、远程仓库变更或公开操作。新增文件交由用户确认是否提交。

## 1. 新增、修改及保留的文件

### 建议用户审查的本次交付文件

| 文件 | 本次操作 | 内容 |
| --- | --- | --- |
| `README.md` | 新增 | 项目介绍、实际技术版本与目录、四类业务、角色与归属权限、启动前提、环境变量和 JWT 安全配置、空库初始化、16 个 API、测试运行方法、已知测试限制和 Git/产物分发说明 |
| `schema.sql` | 新增 | 从实际 `SHOW CREATE TABLE` 核对的 users/device/reservation/repair_order 四表；保留字段、默认值、索引、实际外键、InnoDB 与字符集；无数据行，无建库/选库/删除语句 |
| `DEV-030_DELIVERY_REPORT.md` | 新增 | 本报告；记录证据来源、命令、结果、边界和待用户确认的提交范围 |

**本次没有修改任何既有版本管理文件。** 对开始时记录的 `src/`、`pom.xml`、`.gitignore`、`.mvn/`、`mvnw`、`mvnw.cmd` 文件 SHA-256 逐项复核，均保持一致。生产 Java、测试 Java、POM、示例配置、实际本地配置和业务库表结构均未改动。

### 执行验证产生的本地材料

- `target/`：Maven 编译输出、Surefire XML/文本、可执行 JAR；均受现有忽略规则保护。测试后又执行 package（跳过重复测试），当前 Surefire 同名报告对应全开测试，后续运行会刷新。
- 既有 `verification/dev027/latest-evidence.txt`、`verification/dev029/latest-evidence.txt`：由现有测试自动刷新，已被忽略，不列为本次待提交文件。
- 项目外任务工作目录 `dev030-work/`：本次只读结构/行指纹审计器、运行脚本、原始日志、默认/全开测试摘要、临时安全配置副本及结构脚本草稿。未将辅助脚本加入业务项目，不作为仓库交付依赖。日志和原始 Surefire XML 不作为公开附件。
- 本次新增 5 个独立测试/验证库，最终 11 张测试表全部为空；保留空库，不执行 DROP DATABASE。详情见第 4 节。

任务开始时已有未跟踪材料：`DEV-027_RESERVATION_CANCEL_VERIFICATION.md`、`DEV-029_FULL_REGRESSION_REPORT.md`、`RESERVATION_IDENTITY_VERIFICATION.md` 和 `verification/`。它们不是本次新增的交付文件，未擅自加入暂存区或改写历史报告。

## 2. 事实来源及关键核对

| 核查来源 | 已确认事实 |
| --- | --- |
| `pom.xml` | Spring Boot 4.0.8、Java 21、MyBatis starter 4.0.1、JJWT 0.13.0；有 Boot Maven 插件；没有专门启用三个测试开关的 profile 或 Failsafe 配置 |
| 四个 Controller、四个 Service、Mapper、DTO、实体及异常处理 | 16 个 HTTP 路由；写接口多数返回影响行数 1，登录返回原始 JWT；没有统一响应封装；没有预约查询 API；设备更新不修改编码/状态 |
| `SecurityConfig`、`JwtAuthenticationFilter`、`JwtUtil`、`UserService` | JWT 无状态认证；公开注册/登录；管理员写权限；预约取消/工单详情有归属控制；每次按数据库当前角色授权；注册角色为 USER，散列为 BCrypt；示例令牌时长 1 小时 |
| `application-example.properties` 与本地配置的键/占位符检查 | 示例四项敏感连接/签名配置依赖环境变量；`.env` 没有自动加载机制。本地配置存在机器专属字面量，未打印或复制其值 |
| 实际数据库元数据 | MySQL 8.0.46；四表 InnoDB、utf8mb4_0900_ai_ci；字段、默认值、唯一键及外键以实际元数据为准 |
| 六个测试类及 JUnit 条件注解 | 默认执行 18、条件跳过 32；全量需三个系统属性；旧身份绑定测试依赖真实 USER id=1；两组测试直接读文件且不展开 DB_URL |
| DEV-029 报告及历史 Surefire | 用于交叉核对；本报告测试通过数以本次另行运行结果为准 |

特别纠正常见误述：

1. `reservation.user_id` **实际没有外键**，没有为了文档好看而补写约束；`repair_order` 的 device_id/user_id 均有外键。
2. 维修更新时间实际列名为 **`update_at`**，Mapper 别名映射到 `updatedAt`，没有导出不存在的 `updated_at` 列。
3. 预约冲突只统计 APPROVED；重叠 PENDING 可共存；首尾相接不算冲突。管理员取消同样受“未开始”的限制。
4. 没有前端、Swagger 页面、角色提升接口、预约拒绝或工单取消功能；README 不宣称具备这些能力。
5. 默认 BUILD SUCCESS 不表示 50 个方法全部执行；HTTP 请求数、JUnit 方法数与业务条目数没有混为一个指标。

## 3. 测试实测结果

### 本次命令和汇总

在项目根目录执行，使用现有本地配置和现有开发 MySQL：

```powershell
mvn test
.\mvnw.cmd test '-Dfull.regression.it=true' '-Dreservation.identity.it=true' '-Dreservation.cancel.it=true'
.\mvnw.cmd '-DskipTests' package
```

| 执行 | 实际环境/耗时 | 结果 |
| --- | --- | --- |
| 默认 `mvn test` | Maven 3.9.16、Java 21.0.12.1；21.272 秒 | 发现 50，实际执行通过 18，跳过 32，失败 0，错误 0，BUILD SUCCESS |
| Wrapper 三开关全开 | Maven 3.10.0、同一 JDK；35.219 秒 | **50 通过，0 失败、0 错误、0 跳过，BUILD SUCCESS** |
| Wrapper package | Maven 3.10.0；4.280 秒 | BUILD SUCCESS；生成 `target/equipment-management-0.0.1-SNAPSHOT.jar`；此步显式跳过测试，未计作额外测试通过 |

| 测试类 | 方法数 | 默认通过/跳过 | 全开通过/跳过 |
| --- | ---: | --- | --- |
| `EquipmentManagementApplicationTests` | 1 | 1 / 0 | 1 / 0 |
| `JwtAuthenticationIntegrationTest` | 14 | 14 / 0 | 14 / 0 |
| `JwtUtilTest` | 3 | 3 / 0 | 3 / 0 |
| `FullRegressionIntegrationTest` | 11 | 0 / 11 | 11 / 0 |
| `ReservationIdentityBindingIntegrationTest` | 5 | 0 / 5 | 5 / 0 |
| `ReservationCancelOwnershipIntegrationTest` | 16 | 0 / 16 | 16 / 0 |

默认的认证集成测试使用真实嵌入式 HTTP，但 Mapper 为 mock；不能描述为真实 MySQL 全流程。身份绑定测试用 MockMvc 和真实 MySQL，依靠事务回滚；其余两组显式测试使用真实 HTTP 和新建独立库。取消测试的预期故障注入 HTTP 500 最终断言通过，不是新增平台缺陷。

首轮默认测试的本地采集脚本曾因 PowerShell 将 Mockito 的标准错误警告视为终止异常而中断采集；修正采集器后重新执行，以上只计最终完整结果。没有为消除警告修改项目测试或伪造通过数。

### 必须保留的运行前提

- `full.regression.it`、`reservation.cancel.it` 两组直接读本地文件，对 `spring.datasource.url=${DB_URL}` 不做 Spring 占位符展开。README 规定全量测试前仅在忽略的本地文件中设置具体 JDBC URL；用户名/密码仍可为环境变量占位符。没有偷偷修改源码绕过此限制。
- `reservation.identity.it` 要求配置库 `users.id=1` 存在且角色 USER、相关表为 InnoDB、无相关触发器。纯结构脚本不包含该账户，新库应先注册首个 USER。全量测试不是“对任意空库一条命令直接通过”。
- 独立库测试需建库/建表/DML 权限；并发回归需读取 performance_schema 锁等待与线程信息。普通应用账户不应据此被描述为必须拥有这些测试权限。
- `mvn verify` 不会自动打开三个 JUnit 条件；`-o` 只是 Maven 离线解析选项。README 的可复现命令明确列出开关并使用适合 PowerShell 的引号。

## 4. schema.sql、安全和数据库保护

1. 通过 JDBC 读取实际 `SHOW CREATE TABLE`，按 users → device → reservation → repair_order 顺序生成结构脚本，去除实时 `AUTO_INCREMENT=数字` 表选项，保留自增列定义。没有调用数据导出工具，没有把 SELECT 行内容写入脚本或报告。
2. 向新建随机名 `dev030_*` 空库逐句执行交付脚本；4 张表成功创建，导入后均为 0 行。重新读取 `SHOW CREATE TABLE`，仅规范化实时自增计数后逐表完全一致。
3. 没有补造缺失外键，没有增加种子账户/业务设备、密码散列值、JWT、数据库口令、签名密钥、DEFINER 或生产库名。脚本只有注释、`SET NAMES` 和四条 CREATE TABLE，不含 DROP、INSERT、UPDATE、DELETE、USE 或 CREATE DATABASE 语句。
4. 辅助导入器前两次因 JDBC Statement 在切换 catalog 前已创建，尝试建已有 users 表而收到 1050“表已存在”，未覆盖或修改原表。修正为切换后新建 Statement，最终导入验证通过；两次尝试各留下一个空验证库，连同最终导入库共 3 个 dev030 空库保留。该问题属于项目外临时验证器，不是 schema.sql 或业务源码错误。
5. 正常全量测试另建 dev027/dev029 各一个独立库，并完成其自带夹具清理。启动冒烟只向最终 dev030 库注册一个随机口令的临时用户，验证后已定向清理；没有使用原用户口令或从原库复制账户。
6. 测试前后以四张源表按主键排序的完整行内容，在内存中计算 SHA-256 并比较，同时比较行数；最终全部一致。仅本地保存比较基线，报告不公开原始行、账户信息或密码散列。行内容一致不等于自增计数未变化，事务回滚可能消耗自增值。
7. 最终独立 JDBC 审计：**源库四表内容/行数一致；本次检查的 5 个验证库、11 张测试表全部为 0 行；未删除数据库。** 没有批量清理历史库。

## 5. Wrapper 与启动可行性

- `mvnw`、`mvnw.cmd` 和 `.mvn/wrapper/maven-wrapper.properties` 均已跟踪，only-script 类型不要求 `maven-wrapper.jar`，现有忽略此 JAR 的规则无需改动。
- Windows 实际运行 `.\mvnw.cmd -version`，输出 Maven 3.10.0、Java 21.0.12.1；随后实际完成全开测试与 package。
- 在内存流中从配置的 Apache 官方发行地址读取压缩包，计算 SHA-256 并与配置一致：`1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`。没有关闭校验或修改 Wrapper 版本。该值是公开软件发行包摘要，不是用户凭据。
- 将**未修改的** `application-example.properties` 复制为项目外临时 `application.properties`，用环境变量提供独立库连接信息及内存随机 JWT 密钥；通过 `--spring.config.location` 指定该配置启动实际 JAR，验证占位符解析成功。
- 启动仅绑定 `127.0.0.1` 并使用临时可用端口；为避免将框架自动生成的默认用户口令写入临时日志，仅在本次启动命令中关闭该安全自动配置包的日志，未改变认证业务。
- HTTP 冒烟实测：匿名设备分页查询 401；临时用户注册 200、正文 1；登录 200（JWT 仅存内存）；携 JWT 分页查询 200、空设备总数 0。进程已停止，临时用户已清理。
- README 的数据库 `SOURCE` 步骤明确在 MySQL 客户端执行，未误写 PowerShell `<` 重定向；实际已用 JDBC 执行同一 SQL 验证语法和结构。没有单独对 MySQL GUI/CLI 的界面操作取证。
- 本次没有单独驻留运行 `spring-boot:run`，也没有再执行 README 的完整 `clean package` 组合；已核查插件配置、分别完成默认测试和 package，并实际验证生成 JAR 的启动。没有把这些未执行的组合命令写成已实测。Unix `mvnw`、其他操作系统和公开部署未测试。

## 6. Git 忽略与分享检查

- 本地 `src/main/resources/application.properties` 被忽略且未跟踪；本任务未读取显示其敏感值，也未覆盖该文件。`application-example.properties` 已跟踪且仅含变量占位符。
- `git check-ignore` 实际确认本地配置、target 报告、`.env`、Apifox runtime-local 运行态受忽略规则保护；现有规则还覆盖 IDE 文件、日志、依赖缓存和自动生成的证据/报告。本次不需要修改 `.gitignore`。
- 对开始时 Git 可纳入的 70 个已跟踪/未忽略文件检查了 JWT、BCrypt 散列字面量模式，以及内存中取得的本地敏感配置字面量是否出现；未发现匹配。新增三个交付文件落盘后，对全部 73 个候选文件重复检查，仍未发现匹配；新增三文件另外逐项检查本地口令字面量，未发现匹配。该检查不等于完整历史仓库审计或任意未知秘密的证明。
- 本次未将原始日志、Surefire XML、编译产物、临时配置或数据库快照加入交付范围。**当前本地 POM 会将本地 application.properties 打入 JAR**；该文件虽被 Git 忽略，本机生成 JAR 仍应仅用于本地验证，不能直接作为安全可分发包。README 已明确干净环境和占位符配置构建要求。
- 最终 `git status --short` 对照初始状态，仅新增上述三个待审查文件；它们均未被忽略。已确认 tracked diff 与 staged diff 均为空、`git diff --check` 通过；暂存区保持原样，没有 Git 自动提交、推送或改远程操作。不建议 `git add .` 混入历史未跟踪材料。

## 7. 遗留问题与交付边界

| 项目 | 当前处理/影响 |
| --- | --- |
| 两组测试的 DB_URL 占位符不展开 | 已在 README 给出仅修改本地配置的操作前提；未改测试实现 |
| 身份绑定测试依赖 id=1 USER | 已明确新库先注册首个普通用户；无共享种子账户；未来可单独安排测试自包含改造 |
| 测试高权限与空库积累 | 已列明专用测试实例/权限及保留空库行为；进程强杀后仍需定向检查遗留夹具 |
| Maven 可打包本地配置 | 已明确本地 JAR 禁止直接分发；本次只交付文档和纯结构 SQL，不交付 JAR |
| 预约缺少列表/详情，创建响应不返回 ID | 如实记录现有接口边界；未新增 API |
| 业务规则范围 | 未增加过去时间、设备状态预约限制、注销/刷新令牌、CORS、HTTPS 部署或新密码策略 |
| 验证范围 | 无前端/Apifox GUI、Linux/Unix 脚本、外部部署、容量压测或完整渗透测试；不作超出测试范围的保证 |

目前没有因本次整理而遗留的测试失败或业务源码修改。以上可移植性/分发限制明确留档，不表述为“项目已经满足所有生产部署要求”。后续由用户与老师系统学习项目结构、业务流程、事务与并发、JWT 和权限控制，再进行简历表述与面试训练；本次没有替用户宣称已掌握这些内容。

## 8. 交用户确认的提交范围

建议本次仅审查以下三项：

```text
README.md
schema.sql
DEV-030_DELIVERY_REPORT.md
```

是否一并提交既有历史报告与 verification 脚本，另由用户决定。确认前不进行暂存、提交、推送或公开仓库。
