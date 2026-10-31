package com.ticketing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticketing.perf-log")
public class TicketPerfProperties {

    /**
     * 开启 HTTP 耗时 Filter 与 Hikari 连接池监控日志。
     */
    private boolean enabled = false;
}
