package com.ticketing.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Data @Component @ConfigurationProperties(prefix="ticketing.dev")
public class TicketDevProperties { private boolean exposeLoginCode=false; }
