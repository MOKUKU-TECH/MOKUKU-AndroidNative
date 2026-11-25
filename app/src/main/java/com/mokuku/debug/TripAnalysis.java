package com.mokuku.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Harsh driving events and a driving style for one trip, from its 1 Hz points: acceleration and
 * braking from the change in GPS speed, cornering from GPS speed times the IMU yaw rate.
 */
final class TripAnalysis {
    // Common telematics limits, in m/s².
    static final double HARSH_ACCELERATION = 3.0;
    static final double HARSH_BRAKING = -3.5;
    static final double HARSH_TURN = 3.5;
    // Points further apart than this span a GPS outage, so their speed change is not an acceleration.
    private static final double MAXIMUM_STEP_SECONDS = 1.5;
    private static final double MOVING_METERS_PER_SECOND = 2;
    private static final double STOPPED_METERS_PER_SECOND = 1;
    private static final double MINIMUM_RATED_METERS = 1000;
    private static final int MINIMUM_RATED_SAMPLES = 60;

    enum Style {
        TOO_SHORT("Too short to rate", "A style needs at least 1 km and a minute of driving."),
        AGGRESSIVE("Aggressive", "Frequent harsh acceleration, braking, or cornering."),
        SPORTY("Sporty", "Strong acceleration, braking, or cornering, but rarely harsh."),
        ECO("Eco", "Gentle acceleration and braking, moderate speed, little idling."),
        SMOOTH("Smooth", "Steady driving without harsh events.");

        final String label;
        final String description;
        Style(String label, String description) { this.label = label; this.description = description; }
    }

    final int harshAccelerations;
    final int harshBrakings;
    final int harshTurns;
    // 95th percentile of acceleration, 5th of braking (negative), 95th of lateral acceleration, in m/s², while moving.
    final double typicalAcceleration;
    final double typicalBraking;
    final double typicalLateralAcceleration;
    // False when no point has an IMU yaw rate, so cornering is unknown.
    final boolean yawRateKnown;
    // Share of the trip's time spent stopped.
    final double idleShare;
    final Style style;

    /** timeSeconds and gpsSpeed (m/s) per point; yawRate in rad/s, NaN where the IMU was unavailable. */
    TripAnalysis(double[] timeSeconds, double[] gpsSpeed, double[] yawRate, double distanceMeters, double maximumSpeedKmh) {
        List<Double> accelerations = new ArrayList<>(), lateral = new ArrayList<>();
        int accelerating = 0, braking = 0, turning = 0;
        boolean wasAccelerating = false, wasBraking = false, wasTurning = false;
        double stoppedSeconds = 0;
        for (int i = 0; i < timeSeconds.length; i++) {
            double lateralAcceleration = Double.isNaN(yawRate[i]) ? 0 : gpsSpeed[i] * yawRate[i];
            boolean turn = Math.abs(lateralAcceleration) >= HARSH_TURN;
            if (turn && !wasTurning) turning++;
            wasTurning = turn;
            if (!Double.isNaN(yawRate[i]) && gpsSpeed[i] >= MOVING_METERS_PER_SECOND) lateral.add(Math.abs(lateralAcceleration));
            if (i == 0) continue;
            double step = timeSeconds[i] - timeSeconds[i - 1];
            if (step <= 0 || step > MAXIMUM_STEP_SECONDS) { wasAccelerating = wasBraking = false; continue; }
            if (gpsSpeed[i] < STOPPED_METERS_PER_SECOND) stoppedSeconds += step;
            double acceleration = (gpsSpeed[i] - gpsSpeed[i - 1]) / step;
            boolean accelerate = acceleration >= HARSH_ACCELERATION, brake = acceleration <= HARSH_BRAKING;
            if (accelerate && !wasAccelerating) accelerating++;
            if (brake && !wasBraking) braking++;
            wasAccelerating = accelerate;
            wasBraking = brake;
            if (Math.max(gpsSpeed[i], gpsSpeed[i - 1]) >= MOVING_METERS_PER_SECOND) accelerations.add(acceleration);
        }
        harshAccelerations = accelerating;
        harshBrakings = braking;
        harshTurns = turning;
        typicalAcceleration = percentile(accelerations, 95);
        typicalBraking = percentile(accelerations, 5);
        typicalLateralAcceleration = percentile(lateral, 95);
        yawRateKnown = !lateral.isEmpty();
        double duration = timeSeconds.length == 0 ? 0 : timeSeconds[timeSeconds.length - 1] - timeSeconds[0];
        idleShare = duration > 0 ? stoppedSeconds / duration : 0;

        int harsh = harshEvents();
        if (distanceMeters < MINIMUM_RATED_METERS || accelerations.size() < MINIMUM_RATED_SAMPLES) style = Style.TOO_SHORT;
        else if (harsh >= 2 && harsh / (distanceMeters / 10000) >= 2) style = Style.AGGRESSIVE;
        else if (harsh > 0 || typicalAcceleration >= 1.8 || typicalBraking <= -2.0 || typicalLateralAcceleration >= 2.2) style = Style.SPORTY;
        else if (typicalAcceleration <= 1.0 && typicalBraking >= -1.2 && typicalLateralAcceleration <= 1.5
                && maximumSpeedKmh <= 100 && idleShare <= 0.2) style = Style.ECO;
        else style = Style.SMOOTH;
    }

    int harshEvents() { return harshAccelerations + harshBrakings + harshTurns; }

    private static double percentile(List<Double> values, int percent) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, percent * sorted.size() / 100));
    }
}
