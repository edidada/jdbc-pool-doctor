package com.example.pooldoctor.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * 通过反射读取运行时 DataSource 的真实实现类与生效参数。
 * 这正是出事团队漏掉的一步：只看了配置文件，没确认实际生效的池子。
 */
public class PoolInspector {

    private static final Logger log = LoggerFactory.getLogger(PoolInspector.class);

    public PoolInfo inspect(DataSource dataSource) {
        String className = dataSource.getClass().getName();
        boolean druid = className.contains("druid");
        boolean hikari = className.contains("hikari");
        String activePool = druid ? "Druid" : hikari ? "HikariCP" : "未知";

        long druidMaxWait = readLong(dataSource, "getMaxWait", -1);
        int druidMaxActive = readInt(dataSource, "getMaxActive", -1);
        long hikariConnectionTimeout = readLong(dataSource, "getConnectionTimeout", -1);
        int hikariMaximumPoolSize = readInt(dataSource, "getMaximumPoolSize", -1);

        String effectiveWaitParam;
        long effectiveWaitMs;
        String deadConfig;
        if (druid) {
            effectiveWaitParam = "spring.datasource.druid.max-wait";
            effectiveWaitMs = druidMaxWait;
            deadConfig = "spring.datasource.hikari.connection-timeout=30000 未被读取（HikariCP 未生效）";
        } else if (hikari) {
            effectiveWaitParam = "spring.datasource.hikari.connection-timeout";
            effectiveWaitMs = hikariConnectionTimeout;
            deadConfig = "spring.datasource.druid.max-wait=10000 未被读取（Druid 未生效）";
        } else {
            effectiveWaitParam = "n/a";
            effectiveWaitMs = -1;
            deadConfig = "n/a";
        }

        return new PoolInfo(className, activePool, druidMaxWait, druidMaxActive,
                hikariConnectionTimeout, hikariMaximumPoolSize,
                effectiveWaitParam, effectiveWaitMs, deadConfig);
    }

    public void log(PoolInfo info) {
        log.info("========== 运行时连接池探针 ==========");
        log.info("实际 DataSource 实现类        : {}", info.dataSourceClass());
        log.info("实际生效的连接池              : {}", info.activePool());
        log.info("Druid    maxWait             : {} ms", info.druidMaxWaitMs());
        log.info("Druid    maxActive           : {}", info.druidMaxActive());
        log.info("HikariCP connectionTimeout   : {} ms", info.hikariConnectionTimeoutMs());
        log.info("HikariCP maximumPoolSize     : {}", info.hikariMaximumPoolSize());
        log.info("决定「获取连接超时」的参数    : {} = {} ms",
                info.effectiveWaitParam(), info.effectiveWaitMs());
        log.info("死配置（写了但没人读）        : {}", info.deadConfig());
        log.info("=====================================");
    }

    private static long readLong(Object target, String method, long defaultValue) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Number number ? number.longValue() : defaultValue;
        } catch (ReflectiveOperationException e) {
            return defaultValue;
        }
    }

    private static int readInt(Object target, String method, int defaultValue) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Number number ? number.intValue() : defaultValue;
        } catch (ReflectiveOperationException e) {
            return defaultValue;
        }
    }
}
