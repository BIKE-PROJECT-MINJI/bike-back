package com.bikeprojectminji.bikeback.global.logging;

public final class RequestPathSanitizer {

    private static final String CLIENT_RIDE_RECEIPT_PREFIX =
            "/api/v1/ride-records/by-client-ride-id/";
    private static final String CLIENT_RIDE_RECEIPT_TEMPLATE =
            CLIENT_RIDE_RECEIPT_PREFIX + "{clientRideId}";

    private RequestPathSanitizer() {
    }

    public static String sanitize(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(CLIENT_RIDE_RECEIPT_PREFIX)) {
            return requestUri;
        }
        return CLIENT_RIDE_RECEIPT_TEMPLATE;
    }
}
