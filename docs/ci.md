# 本次改动说明：GitHub Actions + 本地运行排错

本文记录围绕「让这个复现工程在 CI 上自动跑通」所做的改动、验证证据，以及 IDEA 本地启动报
`Access denied` 的定位过程。

## 1. 做了什么（一句话版）

新增 `.github/workflows/ci.yml`：在 GitHub Actions 上用真实 MySQL 8.4 启动应用，分别以
**默认配置 / hikari / druid-fixed** 三种方式调用 `/probe`、`/slow`、`/repro`，并断言
「默认配置必须复现约 10s 的获取连接超时，两个修复方案必须不再误判」；断言失败即 CI 失败。

- 新增文件：`.github/workflows/ci.yml`
- 文档更新：`README.md` 增加「CI：GitHub Actions 自动验证」与「在 IntelliJ IDEA 里运行」两节
- 本文件：`docs/ci.md`

## 2. 工作流做了什么

文件：`.github/workflows/ci.yml`

| 项 | 内容 |
| --- | --- |
| 触发 | `push`（main）、`pull_request`、`workflow_dispatch` |
| 运行环境 | `ubuntu-latest`，JDK 17（temurin），Maven 缓存，`timeout-minutes: 20` |
| 数据库 | service 容器 `mysql:8.4`，库 `pool_doctor`，带 `mysqladmin ping` health check |
| 依赖注入 | `DB_URL` / `DB_USER` / `DB_PASSWORD` 通过 job 级 `env` 传给应用，口令只在 workflow 内临时使用 |
| 产物 | `probe-*.json`、`slow-*.json`、`repro-*.json`、`app-*.log` 作为 artifact 上传（保留 7 天） |

执行步骤：

1. `mvn -B -ntp clean package -DskipTests`
2. 默认配置启动 → `GET /probe`、`GET /slow?seconds=11`、`GET /repro`
3. `--spring.profiles.active=hikari` 启动 → `/probe`、`/repro`
4. `--spring.profiles.active=druid-fixed` 启动 → `/probe`、`/repro`
5. 每次换 profile 前先杀旧进程并等端口释放；失败时打印各份应用日志的尾部
6. 用内置 `python3` 断言（不额外装依赖），任一失败则 step 以非 0 退出

### 断言清单

默认配置（问题态）必须满足：

- 生效池是 Druid（`DruidDataSourceWrapper`），`max-wait=10000ms`，`max-active=1`
- HikariCP 未实例化（`connectionTimeout=-1`），`deadConfig` 指出其配置未被读取
- `/slow?seconds=11` 实测 ≥ 10s（SQL 本身没问题）
- `/repro` 的 T2 `requesterOutcome=TIMEOUT`，等待时间 9–12s；T1 的 11s SQL 正常跑完
- 结论文案包含「HikariCP」与「死配置」

`hikari` 必须满足：

- 生效池是 `HikariDataSource`，`connection-timeout=30000ms`，`maximum-pool-size=10`，Druid 退场
- T2 `SUCCESS`、取连接 < 3s、11s SQL 跑完

`druid-fixed` 必须满足：

- 生效池是 Druid，`max-wait=30000ms`，`max-active=10`
- T2 `SUCCESS`、取连接 < 3s、11s SQL 跑完

## 3. 过程中踩到的两个坑

### 3.1 首次运行被 GitHub 计费拦截

第一次 push 后 job 根本没启动，注解为：

```
The job was not started because recent account payments have failed or your spending limit needs to be increased.
```

原因是仓库为 **private**，Actions 需要计费额度。经确认后把仓库改为 **public**（演示仓库、无口令入库），
标准 runner 免费额度随即恢复。

### 3.2 action 版本告警

初次使用 `checkout@v4` / `setup-java@v4` / `upload-artifact@v4` 触发 Node 20 弃用告警，
已升级为 `checkout@v7` / `setup-java@v6` / `upload-artifact@v7`，输入参数不变，重新运行通过。

## 4. 验证证据

| 提交 | 说明 | run | 结果 |
| --- | --- | --- | --- |
| `73fb29e` | 新增 workflow | [43870776121](https://github.com/edidada/jdbc-pool-doctor/actions/runs/43870776121) | ✅ 1m51s |
| `2cc392d` | 升级 action 版本 + README | [43871517293](https://github.com/edidada/jdbc-pool-doctor/actions/runs/43871517293) | ✅ 1m42s |
| `6ba2a64` | 精简 workflow 空行（作者提交） | [43872497006](https://github.com/edidada/jdbc-pool-doctor/actions/runs/43872497006) | ✅ 1m44s |
| `93d68dd` | README 补充 IDEA 运行说明 | [43873382435](https://github.com/edidada/jdbc-pool-doctor/actions/runs/43873382435) | ✅ |

默认配置的实测输出（CI 日志摘要）：

```
生效超时参数    : spring.datasource.druid.max-wait = 10000 ms
T1 慢 SQL 耗时  : 11001 ms
T2 获取连接     : 10002 ms -> TIMEOUT
结论            : spring.datasource.druid.max-wait=10000ms 在「获取连接」阶段先于 11s 的慢 SQL 触发；
                  HikariCP 的 connection-timeout=30000ms 因未生效而完全没有起作用（死配置）。

生效超时参数    : spring.datasource.hikari.connection-timeout = 30000 ms
T2 获取连接     : 0 ms -> SUCCESS

生效超时参数    : spring.datasource.druid.max-wait = 30000 ms
T2 获取连接     : 0 ms -> SUCCESS

全部断言通过：默认配置稳定复现 10s 误判，两个修复方案均不再误判。
```

## 5. IDEA 启动报 Access denied 的定位

现象：

```
java.sql.SQLException: Access denied for user 'root'@'localhost' (using password: YES)
```

与连接池、代码都无关。对照实验（同一个 jar）：

| 场景 | 结果 |
| --- | --- |
| 不设 `DB_PASSWORD` 直接 `java -jar` | 复现同一条堆栈 |
| 点源 `env.ps1` 后再启动 | 正常，`/probe` 返回 Druid、`maxWait=10000` |

原因：`application.yml` 写的是 `password: ${DB_PASSWORD}`。IDEA 的 Run Configuration 不会执行
`env.ps1`，占位符没有值时会**原样**把 `${DB_PASSWORD}` 当作口令发给 MySQL，因此报
`using password: YES`（该标记只表示发了个非空口令，不代表口令值对）。

修法：Run → Edit Configurations → `ReproApplication` → **Environment variables**，填入
`DB_URL` / `DB_USER` / `DB_PASSWORD`（值取自 `env.local.ps1`）。`.idea/` 已被 `.gitignore` 忽略；
不想在 IDE 里存口令时，改用终端 `.\run.ps1` 启动。

## 6. 常用命令

```powershell
# 查看 / 等待 / 查看日志
gh run list --workflow ci.yml
gh run watch <run-id> --exit-status
gh run view <run-id> --log
gh run view <run-id> --log-failed

# 下载本文提到的产物（探针 JSON 与应用日志）
gh run download <run-id> -n repro-artifacts

# 手动触发一次
gh workflow run ci.yml
```
