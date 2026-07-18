package com.bikeprojectminji.bikeback.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpRequestLoggingFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("HTTP access log는 clientRideId를 숨기고 일반 경로는 유지한다")
    void masksOnlySensitiveReceiptLookupPath() throws Exception {
        HttpRequestLoggingFilter filter = loggingAllRequestsFilter();
        String rawClientRideId = "android-ride-private-001";

        invoke(filter, "/api/v1/ride-records/by-client-ride-id/" + rawClientRideId);
        invoke(filter, "/api/v1/courses/1001/route-points");

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("path=/api/v1/ride-records/by-client-ride-id/{clientRideId}")
                .doesNotContain(rawClientRideId);
        assertThat(appender.list.get(1).getFormattedMessage())
                .contains("path=/api/v1/courses/1001/route-points");
    }

    private HttpRequestLoggingFilter loggingAllRequestsFilter() {
        ObservabilityLoggingProperties properties = new ObservabilityLoggingProperties();
        properties.getHttp().setMode(ObservabilityLoggingProperties.LogMode.ALL);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("observabilityLoggingProperties", properties);
        return new HttpRequestLoggingFilter(beanFactory.getBeanProvider(ObservabilityLoggingProperties.class));
    }

    private void invoke(HttpRequestLoggingFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse).setStatus(200));
    }
}
