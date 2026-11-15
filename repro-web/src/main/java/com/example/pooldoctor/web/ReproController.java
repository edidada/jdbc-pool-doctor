package com.example.pooldoctor.web;

import com.example.pooldoctor.common.PoolInfo;
import com.example.pooldoctor.common.PoolInspector;
import com.example.pooldoctor.common.SlowQueryResult;
import com.example.pooldoctor.common.SlowQueryRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
public class ReproController {

    private static final Logger log = LoggerFactory.getLogger(ReproController.class);
    private static final int SLOW_SECONDS = 11;

    private final DataSource dataSource;
    private final SlowQueryRunner slowQueryRunner = new SlowQueryRunner();
    private final PoolInspector poolInspector = new PoolInspector();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "repro-holder");
        thread.setDaemon(true);
        return thread;
    });

    public ReproController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 启动时第一件事：确认运行时到底用的是哪个连接池。 */
    @PostConstruct
    public void printActivePoolOnStartup() {
        PoolInfo info = poolInspector.inspect(dataSource);
        poolInspector.log(info);
        if ("Druid".equals(info.activePool())) {
            log.warn("类路径下同时存在 HikariCP 与 Druid，当前实际生效的是 Druid；"
                    + "spring.datasource.hikari.connection-timeout=30000 是死配置，不会被读取。");
        }
    }

    @GetMapping("/")
    public Map<String, String> index() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("GET /probe", "打印运行时实际生效的 DataSource 与超时参数");
        endpoints.put("GET /slow?seconds=11", "单独跑一条 11 秒慢 SQL，打印开始/结束时间做基线");
        endpoints.put("GET /repro", "完整复现：11s SQL + 约 10s 获取连接超时 + 死配置");
        return endpoints;
    }

    @GetMapping("/probe")
    public PoolInfo probe() {
        PoolInfo info = poolInspector.inspect(dataSource);
        poolInspector.log(info);
        return info;
    }

    /** 基线：证明 SELECT SLEEP(11) 本身确实要跑约 11 秒。 */
    @GetMapping("/slow")
    public SlowQueryResult slow(@RequestParam(defaultValue = "11") int seconds) {
        Instant start = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            return slowQueryRunner.sleep("baseline", connection, seconds);
        } catch (SQLException e) {
            Instant end = Instant.now();
            log.error("[baseline] 获取连接失败 end={} ex={}", SlowQueryRunner.format(end), e.toString());
            return new SlowQueryResult("baseline", start, end,
                    Duration.between(start, end).toMillis(), seconds, false, e.toString());
        }
    }

    /**
     * 完整复现：
     * T1 占住连接池里唯一的连接执行 SELECT SLEEP(11)；
     * T2 也要执行同一条 11 秒慢 SQL，但先在「获取连接」阶段等待。
     * Druid 的 max-wait=10s 先触发，而团队按 HikariCP 配的 30s 根本没被读取。
     */
    @GetMapping("/repro")
    public ReproReport repro() {
        PoolInfo info = poolInspector.inspect(dataSource);
        poolInspector.log(info);

        log.info("========== 开始复现：双连接池配置错位导致 SQL 超时误判 ==========");
        log.info("场景：连接池上限=1；T1 占住唯一连接执行 SELECT SLEEP({})；T2 也要执行同一条慢 SQL", SLOW_SECONDS);
        log.info("团队认知：HikariCP connection-timeout=30000ms，足以覆盖 11s");
        log.info("实际决定超时的参数：{} = {}ms", info.effectiveWaitParam(), info.effectiveWaitMs());

        CountDownLatch acquired = new CountDownLatch(1);

        // T1：占住唯一连接，跑 11s 慢 SQL
        CompletableFuture<SlowQueryResult> holder = CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                acquired.countDown(); // 此刻唯一连接已被占用
                return slowQueryRunner.sleep("T1-持有连接的慢SQL", connection, SLOW_SECONDS);
            } catch (Exception e) {
                acquired.countDown();
                Instant now = Instant.now();
                log.error("[T1] 异常 {}", e.toString());
                return new SlowQueryResult("T1-持有连接的慢SQL", now, now, 0,
                        SLOW_SECONDS, false, e.toString());
            }
        }, executor);

        try {
            if (!acquired.await(5, TimeUnit.SECONDS)) {
                log.warn("[T1] 5s 内未拿到连接");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            Thread.sleep(300); // 给 T1 一点余量，确保已经进入 SLEEP
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // T2：另一个请求，先取连接，再执行同样的 11s 慢 SQL
        Instant acquireStart = Instant.now();
        Instant acquireEnd = null;
        String outcome;
        String error = null;
        SlowQueryResult requesterQuery = null;
        log.info("[T2] 开始获取连接 start={}", SlowQueryRunner.format(acquireStart));

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            acquireEnd = Instant.now();
            log.info("[T2] 获取连接成功 end={} elapsed={}ms",
                    SlowQueryRunner.format(acquireEnd),
                    Duration.between(acquireStart, acquireEnd).toMillis());
        } catch (Exception e) {
            acquireEnd = Instant.now();
            error = e.getClass().getName() + ": " + e.getMessage();
            log.error("[T2] 获取连接失败/超时 end={} elapsed={}ms ex={}",
                    SlowQueryRunner.format(acquireEnd),
                    Duration.between(acquireStart, acquireEnd).toMillis(), error);
        }

        if (connection != null) {
            outcome = "SUCCESS";
            try {
                requesterQuery = slowQueryRunner.sleep("T2-慢SQL", connection, SLOW_SECONDS);
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 归还连接失败不影响结论
                }
            }
        } else {
            outcome = "TIMEOUT";
        }

        long acquireElapsedMs = Duration.between(acquireStart, acquireEnd).toMillis();
        SlowQueryResult holderQuery = holder.join();

        String conclusion;
        if ("TIMEOUT".equals(outcome)) {
            conclusion = String.format(
                    "%s=%dms 在「获取连接」阶段先于 %ds 的慢 SQL 触发；"
                            + "HikariCP 的 connection-timeout=30000ms 因未生效而完全没有起作用（死配置）。",
                    info.effectiveWaitParam(), info.effectiveWaitMs(), SLOW_SECONDS);
        } else {
            conclusion = String.format(
                    "%s=%dms 生效，T2 成功拿到连接并把 %ds 的慢 SQL 跑完，没有误判超时。",
                    info.effectiveWaitParam(), info.effectiveWaitMs(), SLOW_SECONDS);
        }

        log.info("========== 复现结论 ==========");
        log.info("T1 慢 SQL 实际耗时   : {}ms（SQL 本身确实要 11s）", holderQuery.elapsedMs());
        log.info("T2 获取连接等待       : {}ms -> {}", acquireElapsedMs, outcome);
        log.info("结论                  : {}", conclusion);
        log.info("==============================");

        return new ReproReport(info, info.deadConfig(), SLOW_SECONDS, holderQuery,
                acquireStart, acquireEnd, acquireElapsedMs, outcome, error, requesterQuery, conclusion);
    }
}
