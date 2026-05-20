package com.ansj.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class TraceFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        /*
        String traceId = UUID.randomUUID().toString().replace("-", "");
        ServerHttpRequest request = exchange.getRequest()
            .mutate()
            .header("X-Trace-Id", traceId)
            .build();
        MDC.put("traceId", traceId);

        log.info("received request");

        return chain.filter(
            exchange.mutate()
                .request(request)
                .build()
        ).doFinally(signal -> {
            log.info("finished request");
            MDC.clear();
        });
         */
        log.info("received request: {}", exchange.getRequest().getPath());
        return chain.filter(exchange)
            .doFinally(signal -> log.info("finished request: {}", signal));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
