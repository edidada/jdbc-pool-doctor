package com.example.pooldoctor.web;

import com.example.pooldoctor.common.PoolInfo;
import com.example.pooldoctor.common.SlowQueryResult;

import java.time.Instant;

/**
 * 一次完整复现的结构化报告，便于 curl / 浏览器直接查看，也便于写进日志。
 */
public record ReproReport(
        PoolInfo poolInfo,
        String deadConfig,
        int slowSqlSeconds,
        SlowQueryResult holderQuery,
        Instant acquireStart,
        Instant acquireEnd,
        long acquireElapsedMs,
        String requesterOutcome,
        String requesterError,
        SlowQueryResult requesterQuery,
        String conclusion
) {
}
