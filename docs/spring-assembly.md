# Spring 的自动装配与手动装配机制

> 本文说的「装配」有两个层面，先分清，后面才不会把两个问题混在一起：
>
> 1. **Bean 装配（依赖注入）**：对象之间的依赖关系由谁来建立——`@Autowired` 自动注入，还是手写 `new` / 构造器传参 / `@Bean` 方法内部组装。
> 2. **配置装配（Spring Boot 自动配置）**：基础设施 Bean（`DataSource`、`DispatcherServlet`、事务管理器等）由谁往容器里放——自动配置类按条件注册，还是手写 `@Configuration` 显式声明。
>
> 本工程的坑属于第 2 类：Druid 的**自动配置**抢注了 `DataSource`，Spring Boot 自带的 Hikari 自动配置退场，
> 于是写给 Hikari 的参数没人读。条件细节见 [autoconfiguration.md](autoconfiguration.md)。

---

## 1. 自动装配（一）：Bean 依赖注入

### 1.1 `@Autowired` 的解析顺序

```java
@Service
class OrderService {
    private final OrderRepository repository;
    private final DataSource dataSource;

    // 推荐：构造器注入，字段可以 final，缺依赖时启动即失败
    OrderService(OrderRepository repository, DataSource dataSource) {
        this.repository = repository;
        this.dataSource = dataSource;
    }
}
```

容器按下面的顺序处理一个注入点：

1. **按类型**找候选 Bean；
2. 有多个候选时，依次看 `@Primary` → `@Priority` → 注入点名字（字段名 / 参数名）与 Bean 名匹配；
3. 还定不下来就抛 `NoUniqueBeanDefinitionException`（注意不是「随便选一个」）；
4. 找不到且 `required = true`（默认）→ 抛 `NoSuchBeanDefinitionException`。

配套用法：

| 写法 | 作用 |
| --- | --- |
| `@Qualifier("mysqlDataSource")` | 多候选时显式指定 Bean 名 |
| `@Primary` | 同类型多候选时的默认首选 |
| `List<DataSource>` / `Map<String, DataSource>` | 注入全部同类型 Bean（Map 的 key 是 Bean 名） |
| `Optional<DataSource>` / `ObjectProvider<DataSource>` | 允许依赖不存在（可选依赖、延迟获取） |
| `@Resource(name = "xxx")` | JSR-250，按名字优先 |
| `@Autowired(required = false)` | 找不到时不报错（字段为 null，慎用） |

### 1.2 组件扫描

`@SpringBootApplication` 拆开是 `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`，
扫描范围默认是**启动类所在包及其子包**。本工程启动类在 `com.example.pooldoctor`，
所以 `com.example.pooldoctor.web.ctl` 的 `ReproController` 被扫到；而 `pool-common` 里的
`SlowQueryRunner`、`PoolInspector` 是普通类（没有 `@Component`），由 Controller `new` 出来，不参与注入。

### 1.3 什么时候自动装配会「配错」

自动注入本身只看类型，不关心你「想用哪个」。一旦类路径上有两个 `DataSource`（或一个池子被换掉），
代码仍然能启动、也能注入成功，问题被推迟到运行期——这正是本项目 10 秒超时误判的土壤。
所以关键基础设施 Bean（数据源、事务管理器）必须显式确认「容器里到底是谁」。

---

## 2. 自动装配（二）：Spring Boot 自动配置

### 2.1 入口：`AutoConfiguration.imports`

Spring Boot 2.7 起，自动配置类不再写在 `spring.factories`，而是每个 jar 里放一份：

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

本工程里 Druid starter 的这份文件只有一行：

```
com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure
```

Spring Boot 启动时收集**所有** jar 的这份清单，得到候选自动配置类列表。

### 2.2 两阶段条件评估

| 阶段 | 典型条件 | 判断依据 | 特点 |
| --- | --- | --- | --- |
| 导入筛选（早期） | `@ConditionalOnClass`、`@ConditionalOnWebApplication`、`@ConditionalOnProperty` | 注解元数据（ASM 读取），**不加载类** | 类不在类路径就直接剔除，整套配置连加载都不加载 |
| 注册阶段（后期） | `@ConditionalOnMissingBean`、`@ConditionalOnBean`、`@ConditionalOnSingleCandidate` | 容器里已有的 `BeanDefinition` | 决定「后来者要不要让位」 |

这个分期解释了一个常见疑问：为什么把 Druid 的 jar 排除后，即使 starter 还在，Druid 自动配置也不报
`ClassNotFoundException`——`@ConditionalOnClass` 在筛选阶段就把它剔除了。

### 2.3 排序：不是依赖顺序，是注解顺序

- `@AutoConfigureBefore(X.class)` / `@AutoConfigureAfter(X.class)` 显式声明先后；
- 没有显式关系的，按类名排序（稳定但不可依赖）；
- `@AutoConfiguration` 是 Boot 3 的推荐写法，元注解等价于 `@Configuration(proxyBeanMethods = false)`，并可带 `after` / `before`。

**跟 pom 里依赖的书写顺序无关。** 本工程把 `spring-boot-starter-jdbc` 写在 `druid-spring-boot-3-starter`
前面，Druid 依然先注册，因为它声明了 `@AutoConfigureBefore(DataSourceAutoConfiguration.class)`。

### 2.4 自动配置的属性绑定

自动配置类通常配合：

- `@EnableConfigurationProperties(XxxProperties.class)`：把 `@ConfigurationProperties` 的类注册成 Bean；
- `@ConfigurationProperties(prefix = "...")` 打在 `@Bean` 方法上：Bean 创建后再绑定属性。

一个属性只有**存在对应的 Binder** 时才会生效。`spring.datasource.hikari.connection-timeout` 的 Binder
只存在于 `DataSourceConfiguration.Hikari#dataSource` 上，Hikari 的 `@Bean` 没注册，这个属性就是一段死配置。

### 2.5 覆盖自动配置的四种手段（按推荐顺序）

| 手段 | 写法 | 生效原因 |
| --- | --- | --- |
| 自己声明 Bean | `@Bean DataSource dataSource()` | 用户配置类先处理，自动配置里的 `@ConditionalOnMissingBean` 让位 |
| 用属性开关 | `spring.datasource.type=com.zaxxer.hikari.HikariDataSource` | 自动配置类上的 `@ConditionalOnProperty` 不再匹配 |
| 排除自动配置类 | `@SpringBootApplication(exclude = XxxAutoConfiguration.class)` | 直接把候选剔除 |
| 排除依赖 | `<exclusions>` | `@ConditionalOnClass` 不成立，自动配置整条退场（本工程实测见 autoconfiguration.md） |

自动配置是「兜底」而不是「强制」：只要你先提供，它就让位。反过来，它让位之后，**写给它那个 Bean 的配置属性也一起失效**——这就是本项目「死配置」的由来。

### 2.6 出问题时看哪里

启动加 `--debug`，会打印 `CONDITIONS EVALUATION REPORT`，分 Positive matches / Negative matches，
每条写明是哪个条件、因为什么成立或不成立。本工程的摘录见
[autoconfiguration.md 第 4.3 节](autoconfiguration.md)。

---

## 3. 手动装配

### 3.1 Java Config：`@Configuration` + `@Bean`

```java
@Configuration(proxyBeanMethods = false)
public class ManualDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")   // 也可以不绑，全部手写
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(DruidDataSource.class)     // 显式锁死实现
                .build();
    }
}
```

要点：

- `@Bean` 方法就是工厂方法，方法参数按类型从容器里取 Bean；
- `proxyBeanMethods = true`（默认）时，同一个配置类里互相调用 `@Bean` 方法返回的是同一个实例（CGLIB 代理）；
  设为 `false` 后不再代理，语义更清晰，但类内部直接调用会真的执行方法体；
- `@Bean(initMethod = "init", destroyMethod = "close")` 可指定生命周期方法；
- `@Scope("prototype")`、`@Primary`、`@Qualifier` 与注解装配通用。

**手动声明 `DataSource` 会让两条自动配置都退场**（`@ConditionalOnMissingBean` 都失败），
容器里只有你这一条路。代价是连接池参数、健康检查、指标这些也都要自己管。

### 3.2 XML 装配（存量项目）

```xml
<!-- beans.xml -->
<bean id="dataSource" class="com.zaxxer.hikari.HikariDataSource" destroy-method="close">
    <property name="jdbcUrl" value="${jdbc.url}"/>
    <property name="username" value="${jdbc.user}"/>
    <property name="password" value="${jdbc.password}"/>
    <property name="connectionTimeout" value="30000"/>
</bean>
```

- 传统入口：`ClassPathXmlApplicationContext`；
- Boot 里用 `@ImportResource("classpath:beans.xml")` 混用；
- `<bean>` 与 `@Bean` 等价，都是往容器里注册 `BeanDefinition`，区别只是元数据来源。

### 3.3 更底层：手动注册 `BeanDefinition`

```java
public class DataSourceRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition("dataSource",
                new RootBeanDefinition(DruidDataSource.class));
    }
}
```

- 用 `@Import(DataSourceRegistrar.class)` 引入；
- 也可以实现 `BeanDefinitionRegistryPostProcessor`，或直接 `context.registerBean(...)`；
- 适合动态注册（按配置决定注册几个 Bean）、框架集成（MyBatis 的 Mapper 扫描就是这类思路）。

### 3.4 手动装配的代价

- 优点：**可预测**，容器里有什么一眼可见；参数、生命周期、实现类全在你手里；
- 缺点：样板代码多；基础设施的细节（健康检查、指标、优雅关闭）要自己补齐；多人协作时容易和自动配置打架。

---

## 4. 两条路线对比

| 维度 | 自动装配（Spring Boot） | 手动装配 |
| --- | --- | --- |
| 谁决定 Bean 存在 | 自动配置类 + 条件 | 你写的 `@Bean` / XML / Registrar |
| 配置来源 | `application.yml` → `@ConfigurationProperties` | 你的代码 / XML / 自己绑定的属性 |
| 顺序 | `@AutoConfigureBefore/After` + 类名排序 | 用户配置类先于自动配置 |
| 覆盖方式 | 先给 Bean、改属性、排除自动配置、排除依赖 | 直接改代码 |
| 出问题时的表现 | 静默退场（如本工程 Hikari 的 `hikari.*` 死配置） | 编译期/启动期更早暴露 |
| 适合场景 | 标准组件、希望零配置 | 关键 Bean 要锁死实现、需要非常规组装 |
| 本工程示例 | 默认配置（Druid 自动配置赢）、`hikari`/`druid-fixed` profile（属性开关） | 尚未使用；可加 `@Bean DataSource` 完全接管 |

---

## 5. 落到本工程

1. **默认配置**：类路径上 HikariCP 与 Druid 都在，`spring.datasource.type` 没写，两个属性条件都成立；
   Druid 靠 `@AutoConfigureBefore` 先注册 `dataSource`，Spring Boot 的 `PooledDataSourceConfiguration`
   因 `@ConditionalOnMissingBean(DataSource)` 退出 → 容器里只有 Druid（IoC 断言见 [ioc.md](ioc.md)）。
2. **`hikari` profile**：显式 `spring.datasource.type=com.zaxxer.hikari.HikariDataSource`，
   这是**用属性开关覆盖自动配置**，不是手动 `@Bean`；Druid 的 `@ConditionalOnProperty` 不匹配而退场。
3. **`druid-fixed` profile**：同一个开关指向 Druid，并把 `max-wait` 这类参数写在实际生效的池子上。
4. **要彻底手动接管**：加一个 `@Bean DataSource`（3.1 的写法），两条自动配置都会让位，此时
   `spring.datasource.druid.*` / `spring.datasource.hikari.*` 谁都不会被读取，参数必须写在代码里。

一句话总结：**自动装配是「条件满足就兜底」，手动装配是「我说了算」；危险的不是选了哪个，而是以为选了 A、实际生效的是 B。**

## 6. 相关文档

- 自动装配为什么让 Druid 生效、Hikari 不生效：[autoconfiguration.md](autoconfiguration.md)
- IoC 容器层断言（注入 Druid、无 Hikari）：[ioc.md](ioc.md)
- 接口出入参：[api.md](api.md)
- CI 自动验证：[ci.md](ci.md)
