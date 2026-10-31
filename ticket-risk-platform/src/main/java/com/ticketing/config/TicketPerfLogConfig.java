package com.ticketing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ticketing.perf-log.enabled", havingValue = "true")
public class TicketPerfLogConfig {

    /** Same transaction semantics as the default manager; measures actual begin/commit calls. */
    @Bean
    public org.springframework.transaction.PlatformTransactionManager transactionManager(javax.sql.DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource) {
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                String stage = RequestTimings.transactionStage(".tx_begin");
                long begin = System.nanoTime();
                try { super.doBegin(transaction, definition); }
                finally { RequestTimings.record(stage, begin); }
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                String stage = RequestTimings.transactionStage(".commit");
                long begin = System.nanoTime();
                try { super.doCommit(status); }
                finally { RequestTimings.record(stage, begin); }
            }
        };
    }

    @Bean
    public FilterRegistrationBean<HttpPerfLoggingFilter> httpPerfLoggingFilterRegistration() {
        FilterRegistrationBean<HttpPerfLoggingFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new HttpPerfLoggingFilter());
        bean.addUrlPatterns("/api/tickets/reserve");
        bean.setOrder(Integer.MIN_VALUE);
        return bean;
    }
}
