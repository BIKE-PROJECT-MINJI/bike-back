package com.bikeprojectminji.bikeback.profile.dto;

import com.bikeprojectminji.bikeback.profile.entity.BikeRoadPriority;

public record UpdatePreferenceRequest(
        boolean scenic,
        BikeRoadPriority bikeRoadPriority,
        boolean avoidDust,
        boolean avoidUnsafeSurface
) {
}
