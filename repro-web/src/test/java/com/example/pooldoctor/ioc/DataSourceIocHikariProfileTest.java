package com.example.pooldoctor.ioc;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceWrapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对照组：显式 spring.datasource.type=HikariDataSource（hikari profile）后，
 * IoC 容器里换成了 HikariCP，Druid 没有任何 Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("hikari")
class DataSourceIocHikariProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("hikari profile：IoC 容器注入 HikariCP，Druid 无任何 Bean")
    void hikariInjectedAndDruidAbsent() {
        printDataSourceRelatedBeanDefinitions();

        Map<String, DataSource> dataSources = context.getBeansOfType(DataSource.class);
        assertThat(dataSources).hasSize(1);

        Map.Entry<String, DataSource> entry = dataSources.entrySet().iterator().next();
        assertThat(entry.getKey()).isEqualTo("dataSource");
        assertThat(entry.getValue()).isInstanceOf(HikariDataSource.class);

        BeanDefinition definition = beanDefinition(entry.getKey());
        assertThat(definition.getFactoryBeanName())
                .contains("DataSourceConfiguration$Hikari");
        assertThat(definition.getFactoryMethodName()).isEqualTo("dataSource");

        assertThat(context.getBeanNamesForType(DruidDataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(DruidDataSourceWrapper.class)).isEmpty();
        assertThat(context.getBean(DataSource.class)).isSameAs(entry.getValue());
    }

    private BeanDefinition beanDefinition(String name) {
        return ((ConfigurableApplicationContext) context).getBeanFactory().getBeanDefinition(name);
    }

    private void printDataSourceRelatedBeanDefinitions() {
        System.out.println("[ioc] active profiles = "
                + java.util.Arrays.toString(context.getEnvironment().getActiveProfiles()));
        System.out.println("[ioc] DataSource bean names = "
                + context.getBeansOfType(DataSource.class).keySet());
        System.out.println("[ioc] DruidDataSource bean names = "
                + java.util.Arrays.toString(context.getBeanNamesForType(DruidDataSource.class)));

        for (String name : context.getBeanDefinitionNames()) {
            BeanDefinition definition = beanDefinition(name);
            String factoryBean = definition.getFactoryBeanName();
            boolean related = name.toLowerCase().contains("datasource")
                    || (factoryBean != null && factoryBean.toLowerCase().contains("datasource"));
            if (related) {
                System.out.printf("[ioc] bean=%-12s class=%-55s factoryBean=%s factoryMethod=%s%n",
                        name, definition.getBeanClassName(), factoryBean, definition.getFactoryMethodName());
            }
        }
    }
}
