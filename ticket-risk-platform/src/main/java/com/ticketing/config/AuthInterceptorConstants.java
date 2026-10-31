package com.ticketing.config;
public final class AuthInterceptorConstants {
 private AuthInterceptorConstants() {}
 public static final String[] LOGIN_EXCLUDE_PATTERNS={"/api/user/login","/api/user/code","/error","/actuator/health","/actuator/prometheus"};
 public static boolean isPublicPath(String method,String uri) {
  return ("POST".equals(method) && ("/api/user/login".equals(uri)||"/api/user/code".equals(uri)))
    || ("POST".equals(method) && "/api/payment-gateway/callback".equals(uri)) // mandatory HMAC in the opt-in callback controller
    || ("GET".equals(method) && (uri.startsWith("/api/events") || uri.equals("/api/tickets/reservation-mode") || uri.equals("/actuator/health") || uri.equals("/actuator/prometheus")));
 }
}
