package com.example.pooldoctor.ioc;

import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceWrapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认配置（类路径上同时有 HikariCP 与 Druid）下，直接查 Spring IoC 容器的结论：
 * 注入的是 Druid，HikariCP 连 BeanDefinition 都没有。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DataSourceIocDefaultProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("默认配置：IoC 容器注入 Druid，HikariCP 无任何 Bean")
    void druidInjectedAndHikariAbsent() {
        printDataSourceRelatedBeanDefinitions();

        Map<String, DataSource> dataSources = context.getBeansOfType(DataSource.class);
        assertThat(dataSources).hasSize(1);

        Map.Entry<String, DataSource> entry = dataSources.entrySet().iterator().next();
        assertThat(entry.getKey()).isEqualTo("dataSource");
        assertThat(entry.getValue()).isInstanceOf(DruidDataSourceWrapper.class);

        BeanDefinition definition = beanDefinition(entry.getKey());
        assertThat(definition.getFactoryBeanName())
                .isEqualTo("com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure");
        assertThat(definition.getFactoryMethodName()).isEqualTo("dataSource");

        assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(HikariDataSource.class)).isEmpty();
        assertThat(context.getBean(DataSource.class)).isSameAs(entry.getValue());
    }

    private BeanDefinition beanDefinition(String name) {
        return ((ConfigurableApplicationContext) context).getBeanFactory().getBeanDefinition(name);
    }

    private void printDataSourceRelatedBeanDefinitions() {
        System.out.println("[ioc] DataSource bean names = "
                + context.getBeansOfType(DataSource.class).keySet());
        System.out.println("[ioc] HikariDataSource bean names = "
                + java.util.Arrays.toString(context.getBeanNamesForType(HikariDataSource.class)));

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
