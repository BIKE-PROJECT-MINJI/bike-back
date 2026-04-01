package com.bikeprojectminji.bikeback.dto.weather;

public record WindData(
        Integer speedKmh,
        String directionText,
        Integer directionDeg
) {
}
