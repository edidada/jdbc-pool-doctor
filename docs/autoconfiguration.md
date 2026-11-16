# 自动装配为什么让 Druid 生效、HikariCP 不生效

> 结论先行：两个「DataSource 自动配置」都在抢同一个 `DataSource` Bean。
> Druid 的自动配置通过 `@AutoConfigureBefore` 排在前面，**先**把 `dataSource` 注册进容器；
> Spring Boot 自带的 `PooledDataSourceConfiguration` 用 `@ConditionalOnMissingBean(DataSource.class)` 判断
> 「没人提供数据源时才由我建」，看到已有 Druid 的 `dataSource` 就整体退出。
> 于是 Hikari 的配置类不会被 `@Import` 展开，`spring.datasource.hikari.*` 没有任何绑定对象——死配置。

本文的注解都是从本工程实际依赖的 jar 里用 `javap -v` 读出来的，不是凭印象抄的。

| 组件 | 版本 |
| --- | --- |
| Spring Boot / spring-boot-autoconfigure | 3.2.0 |
| druid-spring-boot-3-starter | 1.2.24（带 druid 1.2.24） |
| HikariCP（由 `spring-boot-starter-jdbc` 引入） | 5.0.1 |
| JDK | 17 |

---

## 1. 三个关键配置类

### 1.1 Druid 的 `DruidDataSourceAutoConfigure`

注册方式：`druid-spring-boot-3-starter-1.2.24.jar` 里的
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 只有一行内容：

```
com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure
```

类上的注解（`javap -v` 结果）：

```java
@Configuration
@ConditionalOnProperty(name = "spring.datasource.type",
        havingValue = "com.alibaba.druid.pool.DruidDataSource",
        matchIfMissing = true)                 // 不写 spring.datasource.type 也匹配
@ConditionalOnClass(DruidDataSource.class)     // 类路径上有 Druid 才生效
@AutoConfigureBefore(DataSourceAutoConfiguration.class)   // 抢在 Spring Boot 前面
@EnableConfigurationProperties({DruidStatProperties.class, DataSourceProperties.class})
@Import({DruidSpringAopConfiguration.class, DruidStatViewServletConfiguration.class,
         DruidWebStatFilterConfiguration.class, DruidFilterConfiguration.class})
public class DruidDataSourceAutoConfigure {

    @Bean
    @ConditionalOnMissingBean({DruidDataSourceWrapper.class, DruidDataSource.class, DataSource.class})
    public DruidDataSourceWrapper dataSource() {
        return new DruidDataSourceWrapper();
    }
}
```

`DruidDataSourceWrapper` 自己带 `@ConfigurationProperties("spring.datasource.druid")`，
所以 `spring.datasource.druid.*`（例如 `max-wait`、`max-active`）是绑定到它身上的——这就是默认配置下真正生效的参数。

### 1.2 Spring Boot 的 `DataSourceAutoConfiguration` 与内部类 `PooledDataSourceConfiguration`

```java
@AutoConfiguration(before = SqlInitializationAutoConfiguration.class)
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @Conditional(PooledDataSourceCondition.class)
    @ConditionalOnMissingBean({ DataSource.class, XADataSource.class })   // ← 决定性条件
    @Import({ DataSourceConfiguration.Hikari.class, DataSourceConfiguration.Tomcat.class,
              DataSourceConfiguration.Dbcp2.class, DataSourceConfiguration.OracleUcp.class,
              DataSourceConfiguration.Generic.class, DataSourceJmxConfiguration.class })
    protected static class PooledDataSourceConfiguration { }
}
```

注意 `@ConditionalOnMissingBean` 挂在这个**内部类**上：只要容器里已经有人注册了 `DataSource`，
整个内部类连同它的 `@Import` 都不成立。

### 1.3 真正 `new HikariDataSource()` 的 `DataSourceConfiguration.Hikari`

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HikariDataSource.class)
@ConditionalOnMissingBean(DataSource.class)
@ConditionalOnProperty(name = "spring.datasource.type",
        havingValue = "com.zaxxer.hikari.HikariDataSource",
        matchIfMissing = true)
static class Hikari {
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")   // hikari.* 只在这里绑定
    HikariDataSource dataSource(DataSourceProperties properties, JdbcConnectionDetails details) { ... }
}
```

`spring.datasource.hikari.connection-timeout`、`maximum-pool-size` 这些属性**只**被这个 `@Bean` 方法上的
`@ConfigurationProperties(prefix = "spring.datasource.hikari")` 读取。

---

## 2. 启动时的解析顺序与条件评估

自动配置类的顺序由 `@AutoConfigureBefore` / `@AutoConfigureAfter` 决定（不是 pom 里依赖的书写顺序）。
Druid 显式声明「在 `DataSourceAutoConfiguration` 之前」，所以先被解析：

```
ConfigurationClassParser（按 AutoConfigureBefore/After 排序后的顺序）
│
├─ DruidDataSourceAutoConfigure
│    @ConditionalOnProperty(name=spring.datasource.type, matchIfMissing=true) → 通过（没写 type）
│    @ConditionalOnClass(DruidDataSource)                                     → 通过（starter 带 druid）
│    @Bean @ConditionalOnMissingBean(DataSource 等)                           → 通过（此刻还没有任何 DataSource）
│    ⇒ 注册 BeanDefinition：dataSource = DruidDataSourceWrapper
│
└─ DataSourceAutoConfiguration
     @ConditionalOnClass(DataSource, EmbeddedDatabaseType) → 通过
     └─ PooledDataSourceConfiguration
          @ConditionalOnMissingBean({DataSource, XADataSource})
            → 不通过：已存在 Druid 注册的 dataSource
          ⇒ 该内部类的 @Import 不展开
             ⇒ DataSourceConfiguration.Hikari 不被注册
                ⇒ 不会 new HikariDataSource
                   ⇒ spring.datasource.hikari.* 没有绑定对象（死配置）
```

条件对照表：

| 条件 | Druid 自动配置 | `PooledDataSourceConfiguration` | `DataSourceConfiguration.Hikari` |
| --- | --- | --- | --- |
| `@ConditionalOnClass` | ✅ `DruidDataSource` 在类路径 | ✅ `DataSource` / `EmbeddedDatabaseType` | ✅ `HikariDataSource` 在类路径 |
| `@ConditionalOnProperty(spring.datasource.type)` | ✅ `matchIfMissing=true`，不写也匹配 | 与 `type` 无关 | ✅ `matchIfMissing=true` |
| `@ConditionalOnMissingBean(DataSource)` | ✅ 此刻还没有 DataSource | ❌ 已有 Druid 的 `dataSource` | 父级未展开，不会注册 |
| 排序 | `@AutoConfigureBefore(DataSourceAutoConfiguration)` | 后 | 内部类 |
| 结果 | **注册 `dataSource`** | 整体退出 | 不实例化 |

两个池子都把 `spring.datasource.type` 当开关，且都写了 `matchIfMissing=true`：
**不配置该属性时两个属性条件都成立**，胜负完全由「谁先注册 Bean」+「`@ConditionalOnMissingBean`」决定。

---

## 3. 为什么 `hikari.*` 是死配置，而 `druid.*` 生效

| 配置前缀 | 谁来绑定 | 默认配置（双池共存）下 |
| --- | --- | --- |
| `spring.datasource.url` / `username` / `password` | `DataSourceProperties`，Druid 自动配置也用它 | 生效（所以库能连上，掩盖了问题） |
| `spring.datasource.druid.*` | `DruidDataSourceWrapper` 上的 `@ConfigurationProperties("spring.datasource.druid")` | **生效**，`max-wait=10000` 就是实际决定超时的参数 |
| `spring.datasource.hikari.*` | 只在 `DataSourceConfiguration.Hikari#dataSource` 的 `@ConfigurationProperties("spring.datasource.hikari")` | **未绑定**，没人读 |

也就是说 `spring.datasource.hikari.connection-timeout=30000` 只是 YAML 里的一行字符串，
既没有 HikariDataSource 实例，也没有任何 Bean 去读它。

---

## 4. 把 Druid 排除后，Hikari 为什么就生效了

### 4.1 条件链重新成立

1. Druid 自动配置类上有 `@ConditionalOnClass(DruidDataSource.class)`。这个条件在**导入筛选阶段**就评估，
   用的是注解元数据（ASM 读取），不会加载该配置类本身；类路径上没有 `DruidDataSource` 就直接把该自动配置剔除。
2. 没有任何自动配置再注册 `DataSource`。
3. `PooledDataSourceConfiguration` 的 `@ConditionalOnMissingBean({DataSource, XADataSource})` 通过 → `@Import` 展开。
4. `DataSourceConfiguration.Hikari` 的 `@ConditionalOnClass(HikariDataSource)` 与
   `@ConditionalOnProperty(matchIfMissing=true)` 通过 → 注册 `dataSource` 并 `new HikariDataSource()`。
5. `spring.datasource.hikari.*` 这时才有绑定对象，原来那段「死配置」**立刻变成生效配置**。

关键点：`@ConditionalOnClass` 判断的是**类路径**，不是「有没有被某个 Bean 使用」。
所以只要 `com.alibaba.druid.pool.DruidDataSource` 这个类不在，Druid 的整套自动装配就退场，
无论 starter jar 是否还在。

### 4.2 两种排除方式（都实测过）

方式 A：直接从依赖里去掉 starter（本工程验证用这种方式）

```xml
<!-- repro-web/pom.xml：删掉这段 -->
<!--
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-3-starter</artifactId>
</dependency>
-->
```

方式 B：保留 starter，只排除 Druid 核心包

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-3-starter</artifactId>
    <version>${druid.version}</version>
    <exclusions>
        <exclusion>
            <groupId>com.alibaba</groupId>
            <artifactId>druid</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

两种方式的实测结果完全一致：

```
# 方式 A 的 fat jar 内 BOOT-INF/lib
HikariCP-5.0.1.jar

# 方式 B 的 fat jar 内 BOOT-INF/lib（starter 还在，核心包没了）
HikariCP-5.0.1.jar
druid-spring-boot-3-starter-1.2.24.jar

# 两种方式的 GET /probe
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

注意 `application.yml` 里 `spring.datasource.hikari.connection-timeout=30000` 和
`maximum-pool-size=10` 一个字没改，只是生效对象从「没人读」变成了 HikariCP 本身；
反过来 `spring.datasource.druid.max-wait=10000` 变成了新的死配置。

### 4.3 `--debug` 条件评估报告对比

默认配置（Druid 生效）：

```
DruidDataSourceAutoConfigure matched:
   - @ConditionalOnClass found required class 'com.alibaba.druid.pool.DruidDataSource' (OnClassCondition)
   - @ConditionalOnProperty (spring.datasource.type=com.alibaba.druid.pool.DruidDataSource) matched (OnPropertyCondition)

DruidDataSourceAutoConfigure#dataSource matched:
   - @ConditionalOnMissingBean (types: DruidDataSourceWrapper,DruidDataSource,javax.sql.DataSource; SearchStrategy: all) did not find any beans (OnBeanCondition)

DataSourceAutoConfiguration.PooledDataSourceConfiguration:
   Did not match:
      - @ConditionalOnMissingBean (types: javax.sql.DataSource,javax.sql.XADataSource; SearchStrategy: all) found beans of type 'javax.sql.DataSource' dataSource (OnBeanCondition)
```

排除 Druid 后（类路径无 Druid）：

```
# 报告中已无任何 Druid 相关条目（类不在类路径，自动配置整条被剔除）

DataSourceAutoConfiguration.PooledDataSourceConfiguration matched:
   - @ConditionalOnMissingBean (types: javax.sql.DataSource,javax.sql.XADataSource; SearchStrategy: all) did not find any beans (OnBeanCondition)

DataSourceConfiguration.Hikari matched:
   - @ConditionalOnClass found required class 'com.zaxxer.hikari.HikariDataSource' (OnClassCondition)
   - @ConditionalOnProperty (spring.datasource.type=com.zaxxer.hikari.HikariDataSource) matched (OnPropertyCondition)
   - @ConditionalOnMissingBean (types: javax.sql.DataSource; SearchStrategy: all) did not find any beans (OnBeanCondition)
```

> 报告里在 Druid 生效时会看到 `DataSourceConfiguration.Hikari` 的类条件也是 matched（有 Hikari 类、没配 `type`），
> 但它的父级 `PooledDataSourceConfiguration` 不成立，`@Import` 不会展开；最终以容器里实际注册的 Bean 为准
> ——`/probe` 里 `hikariConnectionTimeoutMs=-1` 就是「Hikari 根本没被创建」的直接证据。

---

## 5. 让 Hikari 生效的三种做法

| 做法 | 机制 | 本工程对应 | 适用场景 |
| --- | --- | --- | --- |
| 移除/排除 Druid 依赖 | 类路径没有 `DruidDataSource` → Druid 自动配置被 `@ConditionalOnClass` 剔除 | 文档 4.2 的实测 | 确定只用 HikariCP，最彻底，不会误用错池 |
| `spring.datasource.type=com.zaxxer.hikari.HikariDataSource` | Druid 的 `havingValue=DruidDataSource` 不再匹配，Druid 退场；Hikari 的 `havingValue=HikariDataSource` 匹配 | `application-hikari.yml` | 暂时不能动依赖（如共库的其它模块）时的最小改动 |
| 自己声明 `@Bean DataSource` | 用户配置先于自动配置处理，两条 `@ConditionalOnMissingBean(DataSource)` 都会让位 | 未提供 | 需要完全自控（自定义参数来源、加装饰器等） |

`druid-fixed` profile 走的是同一个属性开关的反向：

```yaml
spring:
  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    druid:
      max-active: 10
      max-wait: 30000
```

一旦显式写成 `DruidDataSource`，Hikari 的 `@ConditionalOnProperty` 也不再匹配——两边不会再打架。
三种 profile 的 `/probe` 与 `/repro` 断言见 [ci.md](ci.md)。

---

## 6. 排查清单

- 先看 `/probe` 或启动日志里 `DataSource` 的**实现类**，不要只看 YAML。
- `--debug` 后搜 `DataSourceAutoConfiguration.PooledDataSourceConfiguration` 与
  `DruidDataSourceAutoConfigure`，看是 matched 还是 `Did not match`，以及 `found beans of type 'javax.sql.DataSource' xxx`。
- `mvn dependency:tree -Dincludes=com.alibaba:druid,com.zaxxer:HikariCP` 确认类路径上到底有几个池。
- 直接看 fat jar：`BOOT-INF/lib` 里有没有 `druid-*.jar` / `HikariCP-*.jar`。
- 核对参数名：HikariCP 是 `connection-timeout`（获取连接），Druid 是 `max-wait`；两者语义相近但配置前缀不同。
- 不要用 pom 里依赖的书写顺序推断谁生效——顺序由 `@AutoConfigureBefore`/`@AutoConfigureAfter` 和类名排序决定。

## 7. 相关文档

- 复现与修复步骤：[../README.md](../README.md)
- 接口出入参：[api.md](api.md)
- CI 自动验证：[ci.md](ci.md)
