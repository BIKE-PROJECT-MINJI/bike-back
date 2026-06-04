package com.bikeprojectminji.bikeback.airoute.service;

import java.util.Optional;

public interface UserRoutePreferenceProvider {

    Optional<String> findDefaultRideStyle(String subject);
}
