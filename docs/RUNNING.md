# 新下载者安全运行

## 全新专用演示库

准备JDK21、MySQL8、Maven或Wrapper。首次Wrapper和依赖下载需要网络；前端不需npm生产构建。仅在自己控制的新空equipment_demo库运行V1→V2→V3；若库已存在先核对，不删除或覆盖。不要把下列流程指向已有业务库。

数据库由操作者事先创建，例如在交互式MySQL客户端中执行：

```sql
CREATE DATABASE equipment_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

使用对此专用库有迁移权限的本机账号。没有共享默认密码；不要把真实配置写到源码。示例配置在JAR内通过显式参数加载，无需复制私人application.properties。

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/equipment_demo?serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
$env:DB_USERNAME = Read-Host '专用演示库账号'
$demoPassword = Read-Host '专用演示库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $demoPassword).Password
$demoBytes = New-Object byte[] 32
$demoRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$demoRng.GetBytes($demoBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($demoBytes)
$demoRng.Dispose()
$env:FLYWAY_ENABLED = 'true'
.\mvnw.cmd test
.\mvnw.cmd package '-DskipTests'
java -jar target/equipment-management-0.0.1-SNAPSHOT.jar --spring.config.location=classpath:application-example.properties --server.address=127.0.0.1 --server.port=8080
```

访问本机首页，注册得到USER。ADMIN需操作者在明确的专用库中核对自己的账号后配置；注册接口不能提权，不公开真实账号/散列。示例只在新克隆生成自己的密钥，不用于替换任何已有环境密钥。重启要在私有运行环境安全保留同一密钥，否则旧会话会失效；环境变量仅在当前进程生效。

IDEA打开pom.xml，选JDK21，在运行环境提供上述四个配置并显式启用新库迁移，可用程序参数加载classpath:application-example.properties。不要依赖别人本机IDE设置。.env仅Compose读取，普通Java/IDE没有自动加载它。

## 已有数据环境

先读[DATABASE_MIGRATIONS](DATABASE_MIGRATIONS.md)：检查版本/结构、停旧写入、备份与隔离恢复、显式迁移、数据指纹核对后再切新版。不要直接导入schema.sql、自动baseline未知库或混跑旧新服务。MySQL DDL失败不保证整体回滚。

## Docker待验方案

提供Dockerfile、compose.yaml、.dockerignore、.env.example和docker profile；应用仅本机8081，MySQL不映射主机端口，数据使用独立named volume，不挂载原业务MySQL目录。TZ=Asia/Shanghai，当前没有跨时区预约转换设计。

在本地私有.env或进程环境提供独立DB_PASSWORD、MYSQL_ROOT_PASSWORD、JWT_SECRET，绝不打印展开后的配置。可在另行准备的Docker环境验证：

```powershell
docker compose config --quiet
docker compose up --build -d
```

**Docker实际构建/启动未验证**，不得据模板称一键成功。普通停止保留volume，不对有数据环境执行down -v。镜像构建跳过测试，不等于测试通过；镜像tag未锁digest，不承诺字节级可复现。

测试环境与生产运行分开，详见[TESTING](TESTING.md)。从本机私人配置构建的旧JAR不得直接公开；新包也应检查资源只含安全示例而非真实配置。
