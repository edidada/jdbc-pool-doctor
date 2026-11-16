# 验证：Spring IoC 容器里注入的是 Druid，不是 HikariCP

`/probe` 能告诉你「注入到 Controller 里的那个 `DataSource` 实例是谁」，但那只是**实例层面**的证据。
要证明 **IoC 容器层面**「注册了 Druid、压根没有 HikariCP」，得直接查容器：

- `getBeansOfType(DataSource.class)`：容器里到底有几个数据源 Bean；
- 每个 Bean 的 `BeanDefinition.factoryBeanName` / `factoryMethodName`：它是被哪个配置类、哪个 `@Bean` 方法造出来的；
- `getBeanNamesForType(HikariDataSource.class)`：目标类型是否**完全不存在**。

本工程的验证方式是新增两个 `@SpringBootTest`（`webEnvironment = NONE`，不启 Tomcat，但完整加载业务上下文与连接池），
断言失败即测试失败、CI 失败。

## 1. 结论

| 观察点 | 默认配置 | `hikari` profile |
| --- | --- | --- |
| `getBeansOfType(DataSource.class)` | **1 个**：`dataSource` | **1 个**：`dataSource` |
| 实例类型 | `DruidDataSourceWrapper` | `HikariDataSource` |
| `BeanDefinition.factoryBeanName` | `com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure` | `org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration$Hikari` |
| `BeanDefinition.factoryMethodName` | `dataSource` | `dataSource` |
| `getBeansOfType(HikariDataSource.class)` | **空** | 1 个（就是上面那个 `dataSource`） |
| `getBeansOfType(DruidDataSource.class)` | 1 个（`DruidDataSourceWrapper` 是它的子类） | **空** |
| 配置类 `DataSourceConfiguration$Hikari` 是否在容器里 | **不在**（`@Import` 没展开） | 在 |

一句话：默认配置下，Hikari 连「配置类 Bean」都没进容器；`spring.datasource.hikari.*` 自然没有绑定对象。

## 2. 验证代码

文件：

- `repro-web/src/test/java/com/example/pooldoctor/ioc/DataSourceIocDefaultProfileTest.java`
- `repro-web/src/test/java/com/example/pooldoctor/ioc/DataSourceIocHikariProfileTest.java`（`@ActiveProfiles("hikari")`）

默认配置的关键断言：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DataSourceIocDefaultProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void druidInjectedAndHikariAbsent() {
        Map<String, DataSource> dataSources = context.getBeansOfType(DataSource.class);
        assertThat(dataSources).hasSize(1);

        Map.Entry<String, DataSource> entry = dataSources.entrySet().iterator().next();
        assertThat(entry.getKey()).isEqualTo("dataSource");
        assertThat(entry.getValue()).isInstanceOf(DruidDataSourceWrapper.class);

        BeanDefinition definition = ((ConfigurableApplicationContext) context)
                .getBeanFactory().getBeanDefinition(entry.getKey());
        assertThat(definition.getFactoryBeanName())
                .isEqualTo("com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure");
        assertThat(definition.getFactoryMethodName()).isEqualTo("dataSource");

        // HikariCP 在容器里完全不存在
        assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(HikariDataSource.class)).isEmpty();
    }
}
```

`hikari` profile 的对照组断言（`@ActiveProfiles("hikari")`）：

```java
assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
assertThat(entry.getValue()).isInstanceOf(HikariDataSource.class);
assertThat(definition.getFactoryBeanName())
        .contains("DataSourceConfiguration$Hikari");
assertThat(context.getBeanNamesForType(DruidDataSource.class)).isEmpty();
assertThat(context.getBeansOfType(DruidDataSourceWrapper.class)).isEmpty();
```

两个测试都会遍历容器里的 `BeanDefinition`，只打印名字或工厂 Bean 名里带 `datasource` 的条目（日志前缀 `[ioc]`），
失败时能直接从 CI 日志看到容器里到底有什么。

## 3. 实测输出

本机执行 `mvn -B -ntp test`（JDK 17 / MySQL 8.4.8 / Spring Boot 3.2.0）。

默认配置：

```
[INFO] Running com.example.pooldoctor.ioc.DataSourceIocDefaultProfileTest
[ioc] DataSource bean names = [dataSource]
[ioc] HikariDataSource bean names = []
[ioc] bean=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure class=...$$SpringCGLIB$$0 factoryBean=null factoryMethod=null
[ioc] bean=dataSource   class=null                                                    factoryBean=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure factoryMethod=dataSource
[ioc] bean=...DataSourcePoolMetadataProvidersConfiguration$HikariPoolDataSourceMetadataProviderConfiguration ...
[ioc] bean=hikariPoolDataSourceMetadataProvider class=null                            factoryBean=...DataSourcePoolMetadataProvidersConfiguration$HikariPoolDataSourceMetadataProviderConfiguration factoryMethod=hikariPoolDataSourceMetadataProvider
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

注意两点：

- `HikariDataSource bean names = []`，但容器里确实有一个叫 `hikariPoolDataSourceMetadataProvider` 的 Bean——
  那是 Spring Boot 的**元数据提供器**（给 actuator 之类读池指标用的），不是数据源，别被名字带偏；
- 容器里没有 `org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration$Hikari` 这个配置类 Bean，
  说明它的 `@Import` 在默认配置下根本没展开（对应 [autoconfiguration.md](autoconfiguration.md) 里的条件分析）。

`hikari` profile：

```
[INFO] Running com.example.pooldoctor.ioc.DataSourceIocHikariProfileTest
[ioc] active profiles = [hikari]
[ioc] DataSource bean names = [dataSource]
[ioc] DruidDataSource bean names = []
[ioc] bean=org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration$Hikari class=... factoryBean=null factoryMethod=null
[ioc] bean=dataSource   class=null                                                    factoryBean=org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration$Hikari factoryMethod=dataSource
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

同一份 `application.yml` 一个字没改，只是 `spring.datasource.type` 从「不写」变成 `HikariDataSource`，
容器里的数据源 Bean 就从 Druid 换成了 HikariCP——和 `/probe`、`--debug` 的结论互相印证。

## 4. 自己复跑

```powershell
. .\env.ps1                     # 注入 DB_URL / DB_USER / DB_PASSWORD
mvn -B -ntp test                # 全部模块；测试需要能连上 MySQL

# 只跑 repro-web 及其依赖模块
mvn -B -ntp -pl repro-web -am test

# 看某个测试的完整输出
mvn -B -ntp -pl repro-web -am test -Dtest=DataSourceIocDefaultProfileTest
```

IDEA 里直接 Run 两个测试类即可，注意 Run Configuration 要带 `DB_URL` / `DB_USER` / `DB_PASSWORD`
（原因见 [README](../README.md) 的「在 IntelliJ IDEA 里运行」一节）。

CI：`.github/workflows/ci.yml` 的构建步骤已经是 `mvn -B -ntp clean package`（不再 `-DskipTests`），
这两个断言每次 push 都会执行；失败时 `gh run view <run-id> --log-failed` 能直接看到 `[ioc]` 打印的容器内容。

## 5. 不写测试时，怎么现场看容器

- IDEA Debug 在任意 Bean 里断点，Evaluate Expression 执行：
  `((org.springframework.context.ConfigurableApplicationContext) context).getBeanFactory().getBeanDefinition("dataSource").getFactoryBeanName()`
- 或临时加 `spring-boot-starter-actuator`，访问 `/actuator/beans` 搜 `dataSource`
  （本工程没为了这次排查引入 actuator，避免多一个依赖）。
- 注意：`--debug` 打出来的条件是「谁匹配了」，不是 Bean 列表；最终以容器里注册的 Bean 为准，
  所以上面的断言都直接查 `BeanFactory`。

## 6. 相关文档

- 自动装配为什么让 Druid 生效：[autoconfiguration.md](autoconfiguration.md)
- 接口出入参：[api.md](api.md)
- CI 自动验证：[ci.md](ci.md)
