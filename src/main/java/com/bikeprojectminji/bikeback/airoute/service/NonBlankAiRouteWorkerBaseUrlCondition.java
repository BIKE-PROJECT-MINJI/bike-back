package com.bikeprojectminji.bikeback.airoute.service;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class NonBlankAiRouteWorkerBaseUrlCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String baseUrl = context.getEnvironment().getProperty("ai-route.worker.base-url");
        return baseUrl != null && !baseUrl.isBlank();
    }
}
