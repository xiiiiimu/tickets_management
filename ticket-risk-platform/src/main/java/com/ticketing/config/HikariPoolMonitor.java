package com.ticketing.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;

@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.perf-log.enabled", havingValue = "true")
public class HikariPoolMonitor {

    @Resource
    private DataSource dataSource;

    @Scheduled(fixedRateString = "${ticketing.perf-log.pool-interval-ms:5000}")
    public void logPoolStats() {
        if (!(dataSource instanceof HikariDataSource)) {
            return;
        }
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        log.info("[PERF][pool] active={} idle={} waiting={} total={}",
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(),
                pool.getTotalConnections());
    }
}
