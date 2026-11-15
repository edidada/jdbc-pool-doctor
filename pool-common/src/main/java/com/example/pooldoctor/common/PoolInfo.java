package com.example.pooldoctor.common;

/**
 * 运行时连接池探针结果。重点是 dataSourceClass 与真正决定超时的参数。
 */
public record PoolInfo(
        String dataSourceClass,
        String activePool,
        long druidMaxWaitMs,
        int druidMaxActive,
        long hikariConnectionTimeoutMs,
        int hikariMaximumPoolSize,
        String effectiveWaitParam,
        long effectiveWaitMs,
        String deadConfig
) {
}
