package com.bikeprojectminji.bikeback.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String requestId = RequestLogContext.resolveRequestId(request.getHeader(RequestLogContext.REQUEST_ID_HEADER));
        request.setAttribute(RequestLogContext.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(RequestLogContext.REQUEST_ID_HEADER, requestId);
        RequestLogContext.bind(requestId);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            int status = responseWrapper.getStatus();
            if (status >= 500) {
                log.error("http_request outcome=failure request_id={} method={} path={} status={} duration_ms={} remote_addr={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMs, request.getRemoteAddr());
            } else {
                log.info("http_request outcome=success request_id={} method={} path={} status={} duration_ms={} remote_addr={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMs, request.getRemoteAddr());
            }
            responseWrapper.copyBodyToResponse();
            RequestLogContext.clear();
        }
    }
}
