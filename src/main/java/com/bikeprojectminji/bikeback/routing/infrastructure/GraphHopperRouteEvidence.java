package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import com.bikeprojectminji.bikeback.routing.service.ElevationSummary;
import java.util.List;

record GraphHopperRouteEvidence(
        String summary,
        int bikePathScore,
        int sceneryScore,
        List<RouteEvidenceBadge> badges,
        ElevationSummary elevationSummary
) {
}
