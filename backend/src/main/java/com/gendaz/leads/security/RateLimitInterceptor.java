package com.gendaz.leads.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${app.security.rate-limit.requests-per-window}")
    private int capacity;

    @Value("${app.security.rate-limit.window-seconds}")
    private int windowSeconds;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket(capacity, Instant.now()));
        boolean allowed = bucket.tryConsume(Instant.now(), windowSeconds, capacity);
        int remaining = Math.max(0, bucket.currentTokens(Instant.now(), windowSeconds, capacity) - (allowed ? 1 : 0));
        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"timestamp\":\"" + Instant.now() + "\",\"status\":429,\"code\":\"RATE_LIMITED\"," +
                            "\"message\":\"Muitas requisições. Tente novamente em instantes.\"}");
            return false;
        }
        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static final class Bucket {
        private int tokens;
        private Instant lastRefill;

        Bucket(int tokens, Instant lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }

        synchronized int currentTokens(Instant now, int windowSeconds, int capacity) {
            refill(now, windowSeconds, capacity);
            return tokens;
        }

        synchronized boolean tryConsume(Instant now, int windowSeconds, int capacity) {
            refill(now, windowSeconds, capacity);
            if (tokens >= 1) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill(Instant now, int windowSeconds, int capacity) {
            long elapsed = now.getEpochSecond() - lastRefill.getEpochSecond();
            if (elapsed <= 0) return;
            long added = (elapsed * capacity) / windowSeconds;
            if (added > 0) {
                tokens = Math.min(capacity, tokens + (int) added);
                lastRefill = now;
            }
        }
    }
}
