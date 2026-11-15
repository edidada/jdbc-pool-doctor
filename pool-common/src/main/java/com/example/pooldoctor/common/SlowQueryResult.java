package com.example.pooldoctor.common;

import java.time.Instant;

/**
 * 一次慢 SQL 的执行结果，包含开始时间、结束时间与耗时。
 */
public record SlowQueryResult(
        String label,
        Instant startTime,
        Instant endTime,
        long elapsedMs,
        int requestedSeconds,
        boolean completed,
        String error
) {
}
