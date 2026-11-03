# 测试分层、隔离与证据

## 本次匿名V3工作区实际复验

独立验证副本从本匿名工作区93项白名单文件建立，不含.git、私人application.properties、IDE配置或真实数据。使用Windows、JDK21、Maven3.9.16离线缓存、本机MySQL、Node/Playwright/Edge。

| 本轮实际执行 | 结果 |
| --- | --- |
| Maven compile / app.js语法 | 通过，退出0 |
| 独立Node逻辑 | 8通过、1实时条件项跳过；真实事件请求另由Java HTTP套件执行 |
| 默认JUnit | 111项中39通过、72条件跳过，0失败/错误 |
| 全组JUnit | 111项中110通过、1跳过，0失败/错误；唯一跳过为会生成截图的内置浏览器方法 |
| 其中B2专项 | 13项中12通过、1截图产生项跳过；包含真实V2→V3样本、旧字段保留、权限、重复操作、回滚/锁等待及兄弟工单 |
| 打包与示例启动 | JAR不含私人application.properties，显式加载安全示例在全新合成空库启动；Flyway V1/V2/V3成功且没有默认种子账号/业务数据 |
| 独立合成HTTP | 19条请求符合预期，包含真实注册/登录、正常工单闭环、401/403/409/400等；只针对新合成库 |
| 不截图Edge专项 | 4组通过，1440/390布局、必填说明、真实完成、持续历史缺口/设备暂停、其他USER403；0张新截图 |

本轮独立浏览器检查不是那1项JUnit方法“补计通过”，也不把4组加进JUnit总数。没有复制或使用日常用户数据、真实恢复副本或8080实例；原始证据仅保留仓库外私有位置。示例JAR验证是本机结果，不等同全新电脑冷启动或Docker容器运行。Docker仍未实际验证。

## 不同来源不能混用

主项目此前默认111中39通过/72条件跳过，全量111通过；包含13项B2专项、权限、预约、真实HTTP、实际锁等待与回滚。最终39条只读GET符合预期，使用既有身份的短期内存JWT，非密码登录重验，且没有日常业务写入。

B2历史Edge专项4组/5图，1440与390，真实完成2张合成旧单；503为显式模拟失败，随后200为真实接口成功。平台更早的15组/17图属于旧时点，不能称最新匿名版已完整重跑。用户已报告独立B2人工操作未见明显问题，不补写逐项签署。

本轮不授权新增截图，因此会生成PNG的内置b2.browser用例保持关闭；可另用不截图的合成环境浏览器检查，并单列结果。不能把截图生成用例的跳过说成通过。Docker、全新电脑/公网/容量/全浏览器矩阵未因JUnit通过而完成。

## B3安全测试入口

默认Maven测试不需要数据库。所有Spring测试通过@IsolatedDatabaseTest激活isolated-test，在数据源/Flyway创建前检查最终配置；默认NoDatabaseDriver拒绝联网、Flyway关闭、SQL初始化never。

数据库测试只读TEST_DB_BASE、TEST_DB_USERNAME、TEST_DB_PASSWORD，不从DB_*或私人配置回退。TEST_DB_BASE必须明确本机服务/端口、不带库名或参数；TestDatabase以CREATE DATABASE（无IF NOT EXISTS）新建equipment_it_*并登记，保护不只依赖名字前缀。Spring拒绝未登记URL、额外Flyway/Hikari/JNDI/初始化覆盖。

测试账号需在专用命名空间建库/DDL/DML，锁等待测试需相应performance_schema只读权限。优先专用本机实例/受限账号；不能称同一MySQL实例上的逻辑库隔离为物理隔离。测试不自动删库，新测试必须沿用隔离入口，不能自己读取真实配置。

```powershell
# 无数据库配置的默认项与Node逻辑
.\mvnw.cmd test
node --check src/main/resources/static/assets/app.js
node --test src/test/frontend/repair-status.test.cjs
# 安全提供全部TEST_DB_*后启用全量数据库组；本轮不启用截图产生项
.\mvnw.cmd test '-Dfull.regression.it=true' '-Dreservation.identity.it=true' '-Dreservation.cancel.it=true' '-Drepair.status.it=true' '-Drepair.query.it=true' '-Drepair.maintenance.it=true' '-Drepair.legacy.it=true'
```

独立Node有实时服务条件项，跳过需单列；Java HTTP套件传随机本机端口与合成身份执行真实事件检查。浏览器需Node、Playwright（必要时在自己环境设置NODE_PATH）、已装Edge，不是生产依赖。将来另获截图授权才启用-Db2.browser=true。

IDEA/JUnit也适用同一注解保护。缺安全配置须拒绝，不转向日常库。ApifoxVerificationHarness是依赖本机CLI/模板的历史工具，虽沿用隔离仍不承诺干净检出即可完整运行。

Surefire XML、日志、Token/合成账号、浏览器输出只保留在私有验证目录，不附公开仓库。本文只公布结果摘要；测试代码存在不等于已运行，BUILD SUCCESS不等于条件项全部执行。package -DskipTests也不是测试证据。
