package com.bikeprojectminji.bikeback.global.database;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(HikariDataSource.class)
public class HikariDatabasePoolPressureProbe implements DatabasePoolPressureProbe {

    private final HikariDataSource dataSource;

    public HikariDatabasePoolPressureProbe(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<DatabasePoolSnapshot> snapshot() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return Optional.empty();
        }
        return Optional.of(new DatabasePoolSnapshot(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection()
        ));
    }
}
