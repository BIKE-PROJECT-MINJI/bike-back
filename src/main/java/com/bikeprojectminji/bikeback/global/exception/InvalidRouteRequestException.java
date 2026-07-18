package com.bikeprojectminji.bikeback.global.exception;

public class InvalidRouteRequestException extends BadRequestException {

    public static final String ERROR_CODE = "INVALID_ROUTE_REQUEST";

    public InvalidRouteRequestException(String message) {
        super(message);
    }
}
