package com.bikeprojectminji.bikeback.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);

    private final ObservabilityLoggingProperties loggingProperties;

    public HttpRequestLoggingFilter(ObjectProvider<ObservabilityLoggingProperties> loggingProperties) {
        this.loggingProperties = loggingProperties.getIfAvailable(ObservabilityLoggingProperties::new);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String requestId = RequestLogContext.resolveRequestId(request.getHeader(RequestLogContext.REQUEST_ID_HEADER));
        String traceId = RequestLogContext.resolveTraceId(request.getHeader(RequestLogContext.TRACE_ID_HEADER));
        request.setAttribute(RequestLogContext.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(RequestLogContext.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(RequestLogContext.REQUEST_ID_HEADER, requestId);
        response.setHeader(RequestLogContext.TRACE_ID_HEADER, traceId);
        RequestLogContext.bind(requestId, traceId);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            int status = responseWrapper.getStatus();
            if (loggingProperties.shouldLogHttpRequest(status, durationMs)) {
                if (status >= 500) {
                    log.error("http_request outcome=failure request_id={} trace_id={} method={} path={} status={} duration_ms={} remote_addr={}",
                            requestId, traceId, request.getMethod(), request.getRequestURI(), status, durationMs, request.getRemoteAddr());
                } else if (status >= 400) {
                    log.warn("http_request outcome=client_error request_id={} trace_id={} method={} path={} status={} duration_ms={} remote_addr={}",
                            requestId, traceId, request.getMethod(), request.getRequestURI(), status, durationMs, request.getRemoteAddr());
                } else {
                    log.info("http_request outcome=success request_id={} trace_id={} method={} path={} status={} duration_ms={} remote_addr={}",
                            requestId, traceId, request.getMethod(), request.getRequestURI(), status, durationMs, request.getRemoteAddr());
                }
            }
            responseWrapper.copyBodyToResponse();
            RequestLogContext.clear();
        }
    }
}
