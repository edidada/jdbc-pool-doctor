# 接口说明：URL 与出入参

服务默认监听 `18080`（可用环境变量 `SERVER_PORT` 或启动参数 `--server.port=` 覆盖）。全部接口都是
**GET**、无请求体、无鉴权，响应为 UTF-8 JSON。时间字段是 ISO-8601 的 UTC 时刻（`Instant`），
如 `2026-11-16T13:21:24.35251Z`。

| 接口 | 入参 | 用途 | 典型耗时 |
| --- | --- | --- | --- |
| `GET /` | 无 | 接口清单 | 毫秒级 |
| `GET /probe` | 无 | 运行时实际生效的 DataSource 与超时参数 | 毫秒级 |
| `GET /slow?seconds=11` | `seconds`（可选，int，默认 11） | 基线：单跑一条慢 SQL | ≈ `seconds` 秒 |
| `GET /repro` | 无 | 完整复现：11s 慢 SQL + 约 10s 获取连接超时 | ≈ 11–12s |

---

## 1. `GET /`

入参：无。

出参：`Map<String, String>`，键为接口调用式，值为中文说明。

```json
{
  "GET /probe": "打印运行时实际生效的 DataSource 与超时参数",
  "GET /slow?seconds=11": "单独跑一条 11 秒慢 SQL，打印开始/结束时间做基线",
  "GET /repro": "完整复现：11s SQL + 约 10s 获取连接超时 + 死配置"
}
```

---

## 2. `GET /probe`

入参：无。

出参：`PoolInfo`（`com.example.pooldoctor.common.PoolInfo`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `dataSourceClass` | string | 运行时 `DataSource` 的真实实现类 |
| `activePool` | string | 生效的连接池：`Druid` / `HikariCP` / `未知` |
| `druidMaxWaitMs` | long | Druid `maxWait`，`-1` 表示读不到（Druid 未生效） |
| `druidMaxActive` | int | Druid `maxActive`，`-1` 同上 |
| `hikariConnectionTimeoutMs` | long | HikariCP `connectionTimeout`，`-1` 表示未实例化 |
| `hikariMaximumPoolSize` | int | HikariCP `maximumPoolSize`，`-1` 同上 |
| `effectiveWaitParam` | string | 真正决定「获取连接超时」的配置项名 |
| `effectiveWaitMs` | long | 该配置项的生效值（ms） |
| `deadConfig` | string | 写了但没被任何池子读取的配置说明 |

默认配置（双池共存）实测：

```json
{
  "dataSourceClass": "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceWrapper",
  "activePool": "Druid",
  "druidMaxWaitMs": 10000,
  "druidMaxActive": 1,
  "hikariConnectionTimeoutMs": -1,
  "hikariMaximumPoolSize": -1,
  "effectiveWaitParam": "spring.datasource.druid.max-wait",
  "effectiveWaitMs": 10000,
  "deadConfig": "spring.datasource.hikari.connection-timeout=30000 未被读取（HikariCP 未生效）"
}
```

`--spring.profiles.active=hikari` 实测（字段含义不变，只是生效方换成 HikariCP）：

```json
{
  "dataSourceClass": "com.zaxxer.hikari.HikariDataSource",
  "activePool": "HikariCP",
  "druidMaxWaitMs": -1,
  "druidMaxActive": -1,
  "hikariConnectionTimeoutMs": 30000,
  "hikariMaximumPoolSize": 10,
  "effectiveWaitParam": "spring.datasource.hikari.connection-timeout",
  "effectiveWaitMs": 30000,
  "deadConfig": "spring.datasource.druid.max-wait=10000 未被读取（Druid 未生效）"
}
```

---

## 3. `GET /slow?seconds=11`

入参（query）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `seconds` | int | 否 | `11` | 传给 `SELECT SLEEP(n)` 的秒数；`0` 表示立即返回；非整数由 Spring 返回 400 |

出参：`SlowQueryResult`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `label` | string | 固定为 `baseline` |
| `startTime` / `endTime` | string(ISO-8601) | SQL 开始 / 结束时刻 |
| `elapsedMs` | long | 实际耗时（ms） |
| `requestedSeconds` | int | 请求的 `seconds` |
| `completed` | boolean | SQL 是否执行完成 |
| `error` | string \| null | 取连接或执行失败时的异常字符串，成功为 `null` |

注意：即使取连接失败（如池被占满）也返回 **HTTP 200**，通过 `completed=false` + `error` 表达。

实测：

```json
{
  "label": "baseline",
  "startTime": "2026-11-16T13:21:13.308332500Z",
  "endTime": "2026-11-16T13:21:24.35251Z",
  "elapsedMs": 11044,
  "requestedSeconds": 11,
  "completed": true,
  "error": null
}
```

---

## 4. `GET /repro`

入参：无（慢 SQL 秒数固定为 `11`，即 `slowSqlSeconds`）。

出参：`ReproReport`（`com.example.pooldoctor.web.dto.ReproReport`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `poolInfo` | `PoolInfo` | 同 `/probe` 的结构 |
| `deadConfig` | string | 同 `poolInfo.deadConfig`，提到顶层便于阅读 |
| `slowSqlSeconds` | int | 固定 `11` |
| `holderQuery` | `SlowQueryResult` | T1（占住连接）的慢 SQL 结果，`label=T1-持有连接的慢SQL` |
| `acquireStart` / `acquireEnd` | string(ISO-8601) | T2 开始 / 结束「获取连接」的时刻；失败时 `acquireEnd` 是超时时刻 |
| `acquireElapsedMs` | long | T2 获取连接耗时（ms） |
| `requesterOutcome` | string | `SUCCESS`（拿到连接并跑完慢 SQL）或 `TIMEOUT`（获取连接阶段失败） |
| `requesterError` | string \| null | `TIMEOUT` 时的异常字符串，`SUCCESS` 为 `null` |
| `requesterQuery` | `SlowQueryResult` \| null | T2 的慢 SQL 结果；`TIMEOUT` 时为 `null`（SQL 根本没执行） |
| `conclusion` | string | 中文结论，指出是哪个参数先触发超时 |

该接口无论复现成功与否都返回 **HTTP 200**，判定看 `requesterOutcome`。

默认配置（问题态）实测——T2 在取连接阶段被判超时：

```json
{
  "poolInfo": { "...": "同 /probe 的默认配置输出" },
  "deadConfig": "spring.datasource.hikari.connection-timeout=30000 未被读取（HikariCP 未生效）",
  "slowSqlSeconds": 11,
  "holderQuery": {
    "label": "T1-持有连接的慢SQL",
    "startTime": "2026-11-16T13:21:24.401237700Z",
    "endTime": "2026-11-16T13:21:35.417126800Z",
    "elapsedMs": 11015,
    "requestedSeconds": 11,
    "completed": true,
    "error": null
  },
  "acquireStart": "2026-11-16T13:21:24.713758Z",
  "acquireEnd": "2026-11-16T13:21:34.748877500Z",
  "acquireElapsedMs": 10035,
  "requesterOutcome": "TIMEOUT",
  "requesterError": "com.alibaba.druid.pool.GetConnectionTimeoutException: wait millis 10011, active 1, maxActive 1, creating 0, runningSqlCount 1 : SELECT SLEEP(?)",
  "requesterQuery": null,
  "conclusion": "spring.datasource.druid.max-wait=10000ms 在「获取连接」阶段先于 11s 的慢 SQL 触发；HikariCP 的 connection-timeout=30000ms 因未生效而完全没有起作用（死配置）。"
}
```

`hikari` / `druid-fixed` 实测——T2 立刻拿到连接，两条 11s SQL 都跑完：

```json
{
  "poolInfo": { "...": "HikariCP 生效，connection-timeout=30000、maximum-pool-size=10" },
  "slowSqlSeconds": 11,
  "holderQuery": { "label": "T1-持有连接的慢SQL", "elapsedMs": 11056, "completed": true, "error": null },
  "acquireElapsedMs": 1,
  "requesterOutcome": "SUCCESS",
  "requesterError": null,
  "requesterQuery": {
    "label": "T2-慢SQL",
    "startTime": "2026-11-16T13:21:47.677468400Z",
    "endTime": "2026-11-16T13:21:58.684270100Z",
    "elapsedMs": 11006,
    "requestedSeconds": 11,
    "completed": true,
    "error": null
  },
  "conclusion": "spring.datasource.hikari.connection-timeout=30000ms 生效，T2 成功拿到连接并把 11s 的慢 SQL 跑完，没有误判超时。"
}
```

---

## 5. 调用示例

```powershell
# 默认端口 18080
curl http://localhost:18080/
curl http://localhost:18080/probe
curl "http://localhost:18080/slow?seconds=11"
curl http://localhost:18080/repro

# 只取判定字段
curl -s http://localhost:18080/repro | ConvertFrom-Json |
  Select-Object acquireElapsedMs, requesterOutcome, conclusion
```

CI 中的调用与断言见 [ci.md](ci.md)。
