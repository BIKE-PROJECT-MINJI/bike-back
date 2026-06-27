package com.bikeprojectminji.bikeback.global.database;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnBean(DataSource.class)
public class DatabaseBackpressureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackpressureFilter.class);

    private final DatabasePoolPressureProbe pressureProbe;
    private final DatabaseBackpressureProperties properties;
    private final BikeMetricsRecorder metricsRecorder;
    private final ObjectMapper objectMapper;

    public DatabaseBackpressureFilter(
            DatabasePoolPressureProbe pressureProbe,
            DatabaseBackpressureProperties properties,
            BikeMetricsRecorder metricsRecorder,
            ObjectMapper objectMapper
    ) {
        this.pressureProbe = pressureProbe;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.shouldGuardPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<DatabasePoolSnapshot> snapshot = pressureProbe.snapshot();
        if (snapshot.isPresent() && properties.shouldReject(snapshot.get())) {
            reject(request, response, snapshot.get());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, DatabasePoolSnapshot snapshot)
            throws IOException {
        String reason = properties.rejectionReason(snapshot);
        metricsRecorder.recordDatabaseBackpressureRejected(reason);
        log.warn(
                "database_backpressure_rejected method={} path={} reason={} active={} idle={} max={} pending={}",
                request.getMethod(),
                request.getRequestURI(),
                reason,
                snapshot.activeConnections(),
                snapshot.idleConnections(),
                snapshot.maxConnections(),
                snapshot.pendingThreads()
        );
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiResponse<>(503, properties.getMessage(), null));
    }
}
