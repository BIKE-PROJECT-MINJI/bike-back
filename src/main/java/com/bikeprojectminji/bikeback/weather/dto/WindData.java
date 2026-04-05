package com.bikeprojectminji.bikeback.weather.dto;

public record WindData(
        Integer speedKmh,
        String directionText,
        Integer directionDeg
) {
}
