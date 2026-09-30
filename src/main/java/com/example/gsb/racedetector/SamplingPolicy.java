package com.example.gsb.racedetector;

import java.util.Random;

/** Decides whether a registered access is kept or sampled out. */
@FunctionalInterface
public interface SamplingPolicy {

    boolean sample();

    /** Keep every access. */
    static SamplingPolicy all() {
        return () -> true;
    }

    /** Keep each access independently with probability {@code rate}. */
    static SamplingPolicy uniform(double rate, long seed) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("rate must be within [0,1]: " + rate);
        }
        Random random = new Random(seed);
        return () -> random.nextDouble() < rate;
    }
}
