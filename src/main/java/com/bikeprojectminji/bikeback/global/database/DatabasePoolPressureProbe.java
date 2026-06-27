package com.bikeprojectminji.bikeback.global.database;

import java.util.Optional;

public interface DatabasePoolPressureProbe {

    Optional<DatabasePoolSnapshot> snapshot();
}
