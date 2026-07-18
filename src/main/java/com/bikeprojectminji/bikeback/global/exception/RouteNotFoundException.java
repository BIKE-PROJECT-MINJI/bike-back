package com.bikeprojectminji.bikeback.global.exception;

public class RouteNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "ROUTE_NOT_FOUND";

    public RouteNotFoundException(String message) {
        super(message);
    }
}
