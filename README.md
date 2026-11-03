# 企业资产预约与运维工单协作平台

维护者：**Sweetest**。这是面向Java后端实习求职展示的个人实践项目，以内部资产使用、预约审批和维修协作为场景，不宣称企业生产投产。

Java 21 · Spring Boot 4.0.8 · MyBatis · MySQL 8/InnoDB · Spring Security · JWT/BCrypt · Flyway。前端为同源HTML/CSS/JavaScript，无Vue/React或生产npm构建依赖。

## 实际业务与技术重点

| 模块 | 已实现能力 |
| --- | --- |
| 身份与权限 | 注册固定USER，BCrypt校验与JWT认证；角色从数据库读取；USER只能查询自己的预约/工单，ADMIN处理审批、设备维护和维修 |
| 设备台账 | 分页、状态、详情；管理员增改删，被引用删除409；设备搜索目前在浏览器处理授权目录 |
| 预约 | 创建201并返回Location；分页、状态/设备组合查询；审批、拒绝、开始前取消；同设备APPROVED时间重叠校验 |
| 维修闭环 | PENDING→PROCESSING→COMPLETED；真实操作历史；工单分页与status/deviceId/keyword组合查询 |
| 事务与并发 | READ_COMMITTED、设备行锁、锁后重读、旧状态条件UPDATE及受影响行数校验；工单、设备、历史同事务 |
| 预约联动 | 受理后进入MAINTENANCE；已有批准预约保留并提示人工协调；维修期间禁止新申请与批准 |
| 多工单恢复 | 所有PENDING/PROCESSING结束后才考虑恢复；使用可靠维修前快照，不固定AVAILABLE；人工维修/未知状态保留现状 |
| 旧数据保护 | historyComplete持久保留历史缺口；缺快照或缺受理依据的旧设备暂停自动恢复，管理员填核对说明后可完成工单，暂停不自动解除 |
| Web演示 | 登录、设备、预约、工单、管理员台；真实接口、加载/空态/失败/确认反馈、桌面与390px布局 |
| 迁移 | V1原始四表，V2维修历史/快照/索引，V3历史完整性/恢复暂停/核对说明；不补造旧记录 |

创建人和初始状态由服务端确定。新工单真实CREATED与完整标识同事务写入，后续操作不会把旧历史缺口改为完整。核对说明仅确认本次完成，不代表找回原状态，系统没有强制恢复可用或解除暂停接口。

## 架构与目录

同源Web → JWT过滤器/角色授权 → Controller入口DTO → Service归属与事务 → MyBatis参数绑定SQL → MySQL/InnoDB。

```text
src/main/java/com/example/equipmentmanagement/
  controller/  dto/  service/  mapper/  entity/
  security/  config/  exception/  common/  util/
src/main/resources/static/                  Web演示端
src/main/resources/db/migration/            Flyway V1/V2/V3
src/main/resources/application-example.properties
src/main/resources/application-docker.properties
src/test/java/                             单元/权限/HTTP/并发/回滚/隔离测试
src/test/frontend/                         Node逻辑与可选浏览器测试
src/test/resources/application-isolated-test.properties
schema.sql                                 当前V3空库兼容初始化脚本
```

[架构](docs/ARCHITECTURE.md) · [维修逐层设计](docs/REPAIR_DESIGN.md) · [业务规则](docs/BUSINESS_RULES.md)

## 运行、验证与演示

按[运行说明](docs/RUNNING.md)准备自己的全新演示库及环境变量，显式加载安全示例，Flyway依次执行V1/V2/V3。已有数据环境先看[迁移说明](docs/DATABASE_MIGRATIONS.md)，不能直接套用空库初始化。没有共享默认账号或管理员口令。

匿名V3版本已在独立副本实测：默认JUnit 39通过/72跳过，全组110通过/1项截图产生型用例跳过；另有不截图Edge四组及合成HTTP 19条检查通过。打包JAR和安全示例在新空库初始化V1/V2/V3成功。详见[测试说明](docs/TESTING.md)。主项目此前111项JUnit通过、最终39条只读HTTP符合预期，属于不同来源证据；不自动等同匿名版本已验证。测试报告明确区分通过、失败、跳过和浏览器执行范围。Docker只提供模板，**未实际验证容器构建/运行**。

[USER/ADMIN演示顺序](docs/DEMO_FLOW.md) · [前端说明](docs/FRONTEND_GUIDE.md) · [AI协作实践](docs/AI_COLLABORATION.md) · [发布边界](docs/GITHUB_RELEASE.md)

## 历史页面预览

以下四张图片为已审查的旧版本合成数据截图，**不是当前V3最新界面**，不证明新工单历史/人工核对功能已经在这些图片中展示。本轮保留原图，不新增截图。

![旧版本设备预览](docs/images/devices.jpg)
![旧版本预约预览](docs/images/reservations.jpg)
![旧版本管理员预览](docs/images/admin.jpg)
[旧版登录预览](docs/images/login.jpg)。后续最新截图仅应从合成演示库产生，另行审查。

## 已知边界

无工单指派、取消/重开、附件评论、自动取消预约、刷新令牌或服务端JWT注销；无Redis、消息队列或微服务。设备目录搜索不是服务端搜索，包含关键词查询没有大数据规模性能承诺。未测量QPS、节省工时或生产用户规模。

V3求职展示版本已完成本地整合及测试，公开发布状态以远程仓库为准；本说明不预先宣称推送或公开已完成。AI实际参与工程实现；人工确认业务规则并做操作验收，不宣称全部独立手写或全部已掌握。
