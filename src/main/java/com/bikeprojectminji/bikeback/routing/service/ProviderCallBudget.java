package com.bikeprojectminji.bikeback.routing.service;

import java.util.concurrent.atomic.AtomicInteger;

public final class ProviderCallBudget {

    private final AtomicInteger remaining;

    public ProviderCallBudget(int maximumCalls) {
        if (maximumCalls < 1) {
            throw new IllegalArgumentException("maximumCalls must be positive");
        }
        this.remaining = new AtomicInteger(maximumCalls);
    }

    public boolean tryAcquire() {
        while (true) {
            int current = remaining.get();
            if (current == 0) {
                return false;
            }
            if (remaining.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    public int remaining() {
        return remaining.get();
    }
}
