package com.ticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.MDC;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * HTTP 层总耗时（含 Filter/Interceptor 链），用于对比 JMeter 与 Controller 内部 [PERF][submit]。
 */
@Slf4j
public class HttpPerfLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        RequestTimings.begin();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            String traceId = MDC.get("traceId");
            if (traceId == null) {
                traceId = response.getHeader("X-Trace-Id");
            }
            RequestTimings.finish(response.getStatus(), traceId);
            log.info("[PERF][http] method={} uri={} status={} cost={}ms traceId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costMs,
                    traceId);
        }
    }
}
