package com.ticketing.infrastructure;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
@Component
public class TraceFilter extends OncePerRequestFilter {
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String trace=UUID.randomUUID().toString();MDC.put("traceId",trace);res.setHeader("X-Trace-Id",trace);
  try{chain.doFilter(req,res);}finally{MDC.clear();}
 }
}
