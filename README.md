# README
# Java Web 双连接池配置错位导致 SQL 超时误判

## 背景

现象：一条 SQL 实际执行约 **11 秒**返回。团队在配置中为 HikariCP 设置了 `connection-timeout=30s`，认为超时阈值足够覆盖 11 秒。但实际运行中，该 SQL 仍然抛出超时异常，超时时间约为 **10 秒**。
初步怀疑方向包括：数据库网络连接超时、连接池耗尽、慢 SQL、网络丢包等。经过抓包与配置核对，最终定位为**连接池配置与运行时生效对象错位**。
Java Web 应用，Spring Boot 技术栈。项目类路径下同时存在 **HikariCP** 和 **Druid** 两个连接池依赖。

---

## 问题原因

### 直接原因

运行时实际生效的连接池是 **Druid**，而非团队以为的 **HikariCP**。Druid 的 `maxWait`（从池中获取连接的最大等待时间）使用了默认值，表现为约 10 秒。SQL 执行需要 11 秒，因此在获取连接阶段即被判超时。

团队配置的 HikariCP `connection-timeout=30s` 是一段**死配置**——运行时根本没有走 HikariCP，该参数未被读取。

### 根因：双连接池共存下的自动配置优先级错位

Spring Boot 的自动配置规则：

- 类路径下存在 HikariCP 时，默认优先自动配置 HikariCP。
- Druid 的 starter 也可能通过自身自动配置将 `DataSource` 替换为 `DruidDataSource`。
- 最终谁生效，取决于依赖顺序、是否显式指定 `spring.datasource.type`、以及各 starter 的自动配置条件。

本项目未显式指定 `spring.datasource.type`，导致实际生效的是 Druid，而团队按 HikariCP 的语义去理解和配置超时参数，形成了“配置写给了没在用的池子”的局面。

具体到 `druid-spring-boot-3-starter`：

```java
@ConditionalOnProperty(name = "spring.datasource.type",
        havingValue = "com.alibaba.druid.pool.DruidDataSource",
        matchIfMissing = true)          // 不写 type 时默认匹配
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
public class DruidDataSourceAutoConfigure {
    @Bean @ConditionalOnMissingBean
    public DataSource dataSource() { ... }
}
```

`matchIfMissing = true` + `@AutoConfigureBefore` 让 Druid 抢先注册 `DataSource`，Spring Boot 自带的 Hikari 配置随后因 `@ConditionalOnMissingBean(DataSource.class)` 退场。完整的条件评估过程、`--debug` 报告摘录，以及「排除 Druid 依赖后 Hikari 立刻生效」的实测见 [docs/autoconfiguration.md](docs/autoconfiguration.md)；自动装配与手动装配的机制对比见 [docs/spring-assembly.md](docs/spring-assembly.md)。

### 为什么容易误判

- 两个池子的超时参数名称和语义不同：HikariCP 是 `connection-timeout`，Druid 是 `maxWait`。
- 团队只检查了配置文件里“写了 30s”，没有确认运行时 `DataSource` 的实际实现类。
- 11 秒 SQL 与 10 秒超时的时间差很小，容易被误认为是网络抖动或数据库偶发慢查询。
- 混淆了「获取连接超时」（连接池 `maxWait` / `connection-timeout`）与「SQL 执行超时」（`queryTimeout` / `socketTimeout`）。

---

## 如何解决

核心原则：**不要让两个连接池共存，确保配置作用于实际生效的池子。**

### 方案一：确定使用 Druid

1. 显式指定数据源类型：

   ```properties
   spring.datasource.type=com.alibaba.druid.pool.DruidDataSource
   ```

2. 按 Druid 的语义配置超时，而不是 HikariCP 的 `connection-timeout`；同时给连接池足够的连接数：

   ```yaml
   spring:
     datasource:
       druid:
         initial-size: 2
         max-active: 10
         max-wait: 30000        # 获取连接的最大等待时间，单位 ms
   ```

3. 从依赖中移除 `spring-boot-starter-jdbc` 带来的 HikariCP，保证类路径上只留一个池子。

本工程对应 profile：`druid-fixed`（见 `application-druid-fixed.yml`）。

### 方案二：确定使用 HikariCP

1. 显式指定数据源类型：

   ```properties
   spring.datasource.type=com.zaxxer.hikari.HikariDataSource
   ```

   一旦 `type` 不再是 `DruidDataSource`，上面那条 `@ConditionalOnProperty` 不再匹配，Druid 自动配置退场，Spring Boot 回退到 `DataSourceAutoConfiguration` 创建 `HikariDataSource`。

2. 只保留 `spring.datasource.hikari.*`：

   ```yaml
   spring:
     datasource:
       hikari:
         connection-timeout: 30000
         maximum-pool-size: 10
   ```

3. 从依赖中移除 `druid-spring-boot-3-starter`。

本工程对应 profile：`hikari`（见 `application-hikari.yml`）。

### 无论选哪个池子，都要做的事

- 启动日志里打印 `DataSource` 的实际实现类（本工程 `/probe` 已实现）。
- 核对生效的超时参数名与单位：HikariCP `connection-timeout`(ms) vs Druid `max-wait`(ms)。
- 区分「获取连接超时」与「SQL 执行超时」，不要拿前者的配置去兜后者的底。

---

## 本工程：可运行复现

### 工程结构

```
jdbc-pool-doctor/                 聚合 POM（packaging=pom，Spring Boot 3.2.0）
├── pom.xml
├── pool-common/                 纯 Java 公共库，不绑定 Spring
│   ├── SlowQueryRunner          SELECT SLEEP(n) + 开始/结束时间日志
│   ├── PoolInspector            反射探测运行时 DataSource 与生效参数
│   └── SlowQueryResult/PoolInfo
├── repro-web/                   可运行 Spring Boot Web 应用
│   ├── ReproApplication
│   ├── ReproController          /probe、/slow、/repro
│   └── application.yml          双池共存 + 死配置 + 修复 profile
├── env.ps1                      PowerShell 设置 DB_* 环境变量
├── run.ps1                      创建库 / 构建 / 启动
└── README.md
```

`repro-web` 同时依赖 `spring-boot-starter-jdbc`（引入 HikariCP）与 `druid-spring-boot-3-starter`（引入 Druid），复现“双池共存”的前提条件。慢查询用 `SELECT SLEEP(11)` 模拟，无需建表。

### 环境

- JDK 17、Maven 3.9.x
- MySQL 8.x（本机验证环境 8.4.8，端口 3306）、账号 `root`
- 数据库 `pool_doctor`（`run.ps1` 自动创建）

### 环境变量（PowerShell）

口令不进版本库：`env.ps1` 本身不含任何口令，本机口令放在被 `.gitignore` 忽略的 `env.local.ps1`（模板见 `env.local.ps1.example`）。

```powershell
cp env.local.ps1.example env.local.ps1    # 然后编辑填入本机 MySQL 口令
. .\env.ps1                              # 注意前面的「点 + 空格」，点源才会保留变量
. .\env.ps1 -Password '你的密码'           # 或临时直接传参，不建文件
```

`application.yml` 中只有 `${DB_URL}` / `${DB_USER}` / `${DB_PASSWORD}` 占位符；`DB_PASSWORD` 无默认值，未设置时应用启动即报占位符无法解析，以此强制走环境变量。

等价的手工写法：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/pool_doctor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USER = 'root'
$env:DB_PASSWORD = '你的本机口令'
```

### 构建与启动

```powershell
.\run.ps1                              # 默认：错误配置，复现问题
.\run.ps1 -ActiveProfile hikari        # 方案二：显式使用 HikariCP
.\run.ps1 -ActiveProfile druid-fixed   # 方案一：显式使用 Druid 并修正参数
.\run.ps1 -SkipBuild                   # 已有 jar 时跳过 Maven 构建
```

`run.ps1` 会：点源 `env.ps1` → 用 `mysql.exe` 创建 `pool_doctor` 库 → `mvn clean package -DskipTests` → `java -jar repro-web/target/repro-web-1.0.0.jar`。

### 在 IntelliJ IDEA 里运行

IDEA 的 Run Configuration 不会执行 `env.ps1`。直接点 Run（`ReproApplication`）时 `DB_PASSWORD` 没有值，
`${DB_PASSWORD}` 会**原样**作为口令发给 MySQL，于是启动即报：

```
java.sql.SQLException: Access denied for user 'root'@'localhost' (using password: YES)
```

注意 `using password: YES` 只表示「发了一个非空口令」，不代表口令值对；这条错误与连接池、代码都无关
（CI 里同样的 jar 注入 `DB_*` 环境变量后是跑通的）。

修法：Run → Edit Configurations → `ReproApplication` → **Environment variables**，填入（值取自 `env.local.ps1`）：

```
DB_URL=jdbc:mysql://127.0.0.1:3306/pool_doctor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false
DB_USER=root
DB_PASSWORD=你的本机口令
```

`.idea/` 已被 `.gitignore` 忽略，口令只留在本机的 Run Configuration 里。若不想在 IDE 里存口令，改用终端 `.\run.ps1` 启动即可。

### 接口

| 接口 | 作用 |
| --- | --- |
| `GET /` | 接口清单 |
| `GET /probe` | 打印/返回运行时实际 `DataSource` 实现类与生效的超时参数 |
| `GET /slow?seconds=11` | 基线：单独跑一条 11 秒慢 SQL，打印开始/结束时间与耗时 |
| `GET /repro` | 完整复现：11s 慢 SQL + 约 10s 获取连接超时 + 死配置 |

完整 URL 出入参（字段表 + 实测 JSON）见 [docs/api.md](docs/api.md)。

### 复现步骤

1. `.\run.ps1`，启动日志里先看「运行时连接池探针」。
2. 另开一个 PowerShell 窗口：

   ```powershell
   curl http://localhost:18080/probe
   curl "http://localhost:18080/slow?seconds=11"
   curl http://localhost:18080/repro
   ```

3. 对照应用日志与 `/repro` 返回的 JSON 报告。

`/repro` 的编排：连接池上限设为 1；线程 T1 占住唯一连接执行 `SELECT SLEEP(11)`；线程 T2 也要执行同一条 11 秒慢 SQL，但必须先从池里取连接。T2 在「获取连接」阶段等待，被 Druid `max-wait=10000` 先判超时，SQL 根本没机会执行。

### 预期日志（错误配置）

以下为本机实测输出（MySQL 8.4.8 / JDK 17 / Spring Boot 3.2.0）：

```
========== 运行时连接池探针 ==========
实际 DataSource 实现类        : com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceWrapper
实际生效的连接池              : Druid
Druid    maxWait             : 10000 ms
Druid    maxActive           : 1
HikariCP connectionTimeout   : -1 ms
HikariCP maximumPoolSize     : -1
决定「获取连接超时」的参数    : spring.datasource.druid.max-wait = 10000 ms
死配置（写了但没人读）        : spring.datasource.hikari.connection-timeout=30000 未被读取（HikariCP 未生效）
=====================================
[T1-持有连接的慢SQL] 慢 SQL 开始  start=2026-11-15 23:24:45.656  sql=SELECT SLEEP(11)
[T2] 开始获取连接 start=2026-11-15 23:24:45.962
[T2] 获取连接失败/超时 end=2026-11-15 23:24:55.975 elapsed=10012ms
       ex=com.alibaba.druid.pool.GetConnectionTimeoutException: wait millis 10011, active 1, maxActive 1, creating 0, runningSqlCount 1 : SELECT SLEEP(?)
[T1-持有连接的慢SQL] 慢 SQL 结束  end=2026-11-15 23:24:56.659  elapsed=11003ms  result=完成
结论 : spring.datasource.druid.max-wait=10000ms 在「获取连接」阶段先于 11s 的慢 SQL 触发；
       HikariCP 的 connection-timeout=30000ms 因未生效而完全没有起作用（死配置）。
```

时间线：

```
t=0.0s   T1 拿到唯一连接，开始 SELECT SLEEP(11)
t=0.3s   T2 开始等待获取连接
t=10.3s  Druid max-wait=10000 到期，T2 抛 GetConnectionTimeoutException
t=11.0s  T1 慢 SQL 正常返回（SQL 本身没超时，也不是网络问题）
```

Hikari 的 30s 全程没有参与判断。

### 修复态验证

```powershell
.\run.ps1 -ActiveProfile hikari
```

- `/probe` 变为 `com.zaxxer.hikari.HikariDataSource`，`connectionTimeout=30000`、`maximumPoolSize=10`，`druidMaxWait=-1`（Druid 已退场）。
- `/repro` 中 T2 立刻从池里拿到第二条连接（`acquireElapsedMs=0`），两条 11 秒 SQL 都跑完，`requesterOutcome=SUCCESS`，不再出现 10 秒误判。本机实测 T1 `elapsedMs=11052`、T2 `elapsedMs=11003`。

`.\run.ps1 -ActiveProfile druid-fixed` 则是方案一：仍然是 Druid，但 `max-active=10`、`max-wait=30000`，同样不会误判。

### 可选：在 MySQL 8 侧也留下耗时证据

应用日志已经给出开始/结束时间。若还想让 MySQL 自己记录这条慢 SQL，可开启慢查询日志（改动的是本机 MySQL 全局状态，验证完可关掉）：

```powershell
$env:MYSQL_PWD = $env:DB_PASSWORD
$cli = 'D:\develops\mysql-8.4.8-winx64\bin\mysql.exe'

# 先记下原值，验证完恢复
$row = (& $cli -h 127.0.0.1 -P 3306 -u root -N -e "SELECT @@global.slow_query_log, @@global.long_query_time, @@global.log_output;") -split "`t"

& $cli -h 127.0.0.1 -P 3306 -u root -e "SET GLOBAL slow_query_log='ON'; SET GLOBAL long_query_time=1; SET GLOBAL log_output='TABLE';"

# 注意：long_query_time 在建立连接时复制进会话，应用里已有的长连接不会自动感知新值。
# 所以另开一个 mysql 会话跑慢查询来验证（或改完后重启应用再调 /slow）。
& $cli -h 127.0.0.1 -P 3306 -u root -N -e "SELECT SLEEP(11);"
& $cli -h 127.0.0.1 -P 3306 -u root -e "SELECT start_time, query_time, sql_text FROM mysql.slow_log ORDER BY start_time DESC LIMIT 5\G"

# 恢复原值：slow_query_log 要用 ON/OFF，写成字符串 '0' 会报 ERROR 1231
$restoreLog = if ($row[0] -eq '1') { 'ON' } else { 'OFF' }
& $cli -h 127.0.0.1 -P 3306 -u root -e "SET GLOBAL slow_query_log='$restoreLog'; SET GLOBAL long_query_time=$($row[1]); SET GLOBAL log_output='$($row[2])';"
Remove-Item Env:\MYSQL_PWD
```

本机实测 `mysql.slow_log` 记录：

```
start_time: 2026-11-15 23:27:27.472528
query_time: 00:00:11.012803
  sql_text: SELECT SLEEP(11)
```

`start_time` 即开始时间、`query_time` 即耗时，与应用侧日志互相印证：SQL 确实跑了 11 秒，10 秒的超时来自连接池而非数据库。

### 排查清单

- [ ] 先打印 `DataSource` 的实际实现类，再谈超时参数。
- [ ] 类路径上是否同时存在 HikariCP 与 Druid？
- [ ] 是否显式设置了 `spring.datasource.type`？
- [ ] 写配置的参数名是否属于**实际生效**的那个池子？
- [ ] 超时发生在「获取连接」还是「执行 SQL」？看异常类型与堆栈。
- [ ] 连接池 `max-active` / `maximum-pool-size` 是否被慢 SQL 占满？

---

## CI：GitHub Actions 自动验证

改动明细、验证证据与常见问题见 [docs/ci.md](docs/ci.md)；IoC 容器层「注入的是 Druid、没有 HikariCP」的断言见 [docs/ioc.md](docs/ioc.md)。

`.github/workflows/ci.yml` 在 push / PR 时自动跑一遍「构建 + 复现 + 修复验证」：

1. JDK 17 + Maven 构建；
2. 启动 `mysql:8.4` service 容器（库名 `pool_doctor`，口令走 workflow 内的临时值）；
3. 依次用默认配置、`hikari`、`druid-fixed` 启动应用，调用 `/probe`、`/slow?seconds=11`、`/repro`；
4. 断言默认配置必须复现「约 10s 获取连接超时 + 11s SQL 正常跑完 + HikariCP 配置未被读取」，两个修复 profile 必须 `requesterOutcome=SUCCESS` 且取连接耗时 < 3s；
5. 断言失败即 CI 失败，`probe-*.json` / `slow-*.json` / `repro-*.json` / `app-*.log` 作为 artifact 上传。

用 `gh` 查看运行结果与日志：

```powershell
gh run list --workflow ci.yml
gh run watch <run-id> --exit-status     # 阻塞等待，失败时返回非 0
gh run view <run-id>                    # 概览 + 失败注解
gh run view <run-id> --log              # 全部日志
gh run view <run-id> --log-failed       # 只看失败步骤的日志
```
