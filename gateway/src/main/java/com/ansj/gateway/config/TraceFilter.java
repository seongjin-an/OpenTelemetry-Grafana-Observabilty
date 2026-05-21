package com.ansj.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
@Component
public class TraceFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();

        String method = request.getMethod().name();
        String path   = request.getPath().value();
        String ip     = resolveClientIp(request);
        String user   = resolveUser(request);

        return chain.filter(exchange).doFinally(signal -> {
            long duration = System.currentTimeMillis() - startTime;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;

            log.info("audit",
                    kv("audit",       true),
                    kv("method",      method),
                    kv("path",        path),
                    kv("status",      status),
                    kv("ip",          ip),
                    kv("user",        user),
                    kv("duration_ms", duration),
                    kv("result",      signal.name())   // ON_COMPLETE / ON_ERROR / CANCEL
            );
        });
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String resolveUser(ServerHttpRequest request) {
        // 실제 운영: JWT 파싱 또는 인증 서버 연동으로 교체
        String user = request.getHeaders().getFirst("X-User-Id");
        return user != null ? user : "anonymous";
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
