package com.bikeprojectminji.bikeback.global.validation;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;

public final class CoordinateValidator {

    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    private CoordinateValidator() {
    }

    public static void validateLatitude(String fieldName, BigDecimal latitude) {
        if (latitude == null) {
            throw new BadRequestException(fieldName + "는 비어 있을 수 없습니다.");
        }
        if (latitude.compareTo(MIN_LATITUDE) < 0 || latitude.compareTo(MAX_LATITUDE) > 0) {
            throw new BadRequestException(fieldName + "는 -90 이상 90 이하여야 합니다.");
        }
    }

    public static void validateLongitude(String fieldName, BigDecimal longitude) {
        if (longitude == null) {
            throw new BadRequestException(fieldName + "는 비어 있을 수 없습니다.");
        }
        if (longitude.compareTo(MIN_LONGITUDE) < 0 || longitude.compareTo(MAX_LONGITUDE) > 0) {
            throw new BadRequestException(fieldName + "는 -180 이상 180 이하여야 합니다.");
        }
    }

    public static void validateLatLon(String latitudeFieldName, BigDecimal latitude, String longitudeFieldName, BigDecimal longitude) {
        validateLatitude(latitudeFieldName, latitude);
        validateLongitude(longitudeFieldName, longitude);
    }
}
