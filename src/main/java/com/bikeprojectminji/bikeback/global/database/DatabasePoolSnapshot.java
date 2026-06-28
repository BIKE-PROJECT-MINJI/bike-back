package com.bikeprojectminji.bikeback.global.database;

public record DatabasePoolSnapshot(
        int activeConnections,
        int idleConnections,
        int maxConnections,
        int pendingThreads
) {
}
