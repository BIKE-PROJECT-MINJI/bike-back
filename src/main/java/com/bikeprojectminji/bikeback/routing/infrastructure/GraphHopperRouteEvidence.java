package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import java.util.List;

record GraphHopperRouteEvidence(
        String summary,
        int bikePathScore,
        int sceneryScore,
        List<RouteEvidenceBadge> badges
) {
}
