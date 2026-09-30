package com.example.gsb.racedetector;

import java.util.List;

/** Outcome of running race detection. */
public record RaceDetectionResult(
        List<RaceReport> races,
        RaceStatistics statistics
) {
    public RaceDetectionResult {
        races = List.copyOf(races);
    }

    public boolean hasRaces() {
        return !races.isEmpty();
    }

    public int raceCount() {
        return races.size();
    }
}
