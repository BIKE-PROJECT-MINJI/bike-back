package com.bikeprojectminji.bikeback.condition.service;

public interface RouteConditionClient {

    String source();

    String label();

    RouteConditionEvidence lookup(RouteConditionRequest request);
}
