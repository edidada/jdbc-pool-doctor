package com.example.pooldoctor.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 在已持有的连接上执行 SELECT SLEEP(?)，并打印开始时间、结束时间与耗时。
 */
public class SlowQueryRunner {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryRunner.class);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static String format(Instant instant) {
        return FMT.format(instant);
    }

    public SlowQueryResult sleep(String label, Connection connection, int seconds) {
        Instant start = Instant.now();
        log.info("[{}] 慢 SQL 开始  start={}  sql=SELECT SLEEP({})", label, format(start), seconds);
        try (PreparedStatement ps = connection.prepareStatement("SELECT SLEEP(?)")) {
            ps.setInt(1, seconds);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            Instant end = Instant.now();
            long elapsed = Duration.between(start, end).toMillis();
            log.info("[{}] 慢 SQL 结束  end={}  elapsed={}ms  result=完成", label, format(end), elapsed);
            return new SlowQueryResult(label, start, end, elapsed, seconds, true, null);
        } catch (SQLException e) {
            Instant end = Instant.now();
            long elapsed = Duration.between(start, end).toMillis();
            log.warn("[{}] 慢 SQL 异常  end={}  elapsed={}ms  ex={}: {}",
                    label, format(end), elapsed, e.getClass().getSimpleName(), e.getMessage());
            return new SlowQueryResult(label, start, end, elapsed, seconds, false,
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
