package com.nl2sql.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>优先级最高（{@code @Order(Integer.MIN_VALUE)}），确保所有后续日志均包含 traceId。
 * 优先复用上游传入的 {@code X-Trace-Id} 请求头，否则生成 32 位 UUID。
 * traceId 写入 SLF4J MDC 供日志 pattern 输出（{@code %X{traceId}}），同时回写响应头供前端串联。
 */
@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    /**
     * 读取或生成 traceId，写入 MDC 后放行请求，最终清理 MDC。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
