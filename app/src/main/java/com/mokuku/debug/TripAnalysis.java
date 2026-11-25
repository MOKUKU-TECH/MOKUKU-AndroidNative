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
    // MOKUKU sometimes records one fix twice, tens of milliseconds apart; GPS fixes are 1 s apart.
    private static final double DUPLICATE_SECONDS = 0.5;
    // Acceleration is the speed change across two steps; a longer span crosses a GPS outage.
    private static final double MAXIMUM_SPAN_SECONDS = 3;
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
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < timeSeconds.length; i++) {
            if (kept.isEmpty() || timeSeconds[i] - timeSeconds[kept.get(kept.size() - 1)] >= DUPLICATE_SECONDS) kept.add(i);
        }
        int count = kept.size();
        double[] time = new double[count], speed = cleanSpeeds(kept, timeSeconds, gpsSpeed), lateral = new double[count];
        for (int k = 0; k < count; k++) {
            time[k] = timeSeconds[kept.get(k)];
            double yaw = yawRate[kept.get(k)];
            lateral[k] = Double.isNaN(yaw) ? Double.NaN : speed[k] * yaw;
        }

        List<Double> accelerations = new ArrayList<>(), lateralMoving = new ArrayList<>();
        int accelerating = 0, braking = 0, turning = 0;
        boolean wasAccelerating = false, wasBraking = false, wasTurning = false;
        double stoppedSeconds = 0;
        for (int k = 0; k < count; k++) {
            // The median of three neighbors ignores a single-sample yaw or speed spike.
            double sideways = median(lateral, k);
            boolean turn = !Double.isNaN(sideways) && Math.abs(sideways) >= HARSH_TURN;
            if (turn && !wasTurning) turning++;
            wasTurning = turn;
            if (!Double.isNaN(sideways) && speed[k] >= MOVING_METERS_PER_SECOND) lateralMoving.add(Math.abs(sideways));
            if (k > 0 && time[k] - time[k - 1] <= MAXIMUM_SPAN_SECONDS && speed[k] < STOPPED_METERS_PER_SECOND) stoppedSeconds += time[k] - time[k - 1];
            boolean accelerate = false, brake = false;
            if (k > 0 && k + 1 < count && time[k + 1] - time[k - 1] <= MAXIMUM_SPAN_SECONDS) {
                double acceleration = (speed[k + 1] - speed[k - 1]) / (time[k + 1] - time[k - 1]);
                accelerate = acceleration >= HARSH_ACCELERATION;
                brake = acceleration <= HARSH_BRAKING;
                if (Math.max(speed[k + 1], speed[k - 1]) >= MOVING_METERS_PER_SECOND) accelerations.add(acceleration);
            }
            if (accelerate && !wasAccelerating) accelerating++;
            if (brake && !wasBraking) braking++;
            wasAccelerating = accelerate;
            wasBraking = brake;
        }
        harshAccelerations = accelerating;
        harshBrakings = braking;
        harshTurns = turning;
        typicalAcceleration = percentile(accelerations, 95);
        typicalBraking = percentile(accelerations, 5);
        typicalLateralAcceleration = percentile(lateralMoving, 95);
        yawRateKnown = !lateralMoving.isEmpty();
        double duration = count == 0 ? 0 : time[count - 1] - time[0];
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

    /**
     * GPS speeds of the kept points, with glitches replaced by interpolating between their neighbors:
     * a speed repeated exactly once while moving (the receiver reported no new speed) and a lone zero
     * between moving points.
     */
    private static double[] cleanSpeeds(List<Integer> kept, double[] timeSeconds, double[] gpsSpeed) {
        int count = kept.size();
        double[] time = new double[count], speed = new double[count];
        for (int k = 0; k < count; k++) { time[k] = timeSeconds[kept.get(k)]; speed[k] = gpsSpeed[kept.get(k)]; }
        boolean[] glitch = new boolean[count];
        for (int k = 1; k < count; k++) {
            // Only a single repeat: a longer run is a steady speed, or a stop.
            boolean stale = speed[k] == speed[k - 1] && speed[k] > MOVING_METERS_PER_SECOND
                    && (k < 2 || speed[k - 1] != speed[k - 2]) && (k + 1 == count || speed[k + 1] != speed[k]);
            boolean dropped = k + 1 < count && speed[k] < STOPPED_METERS_PER_SECOND
                    && speed[k - 1] >= MOVING_METERS_PER_SECOND && speed[k + 1] >= MOVING_METERS_PER_SECOND;
            glitch[k] = stale || dropped;
        }
        double[] cleaned = speed.clone();
        int previous = -1;
        for (int k = 0; k < count; k++) {
            if (!glitch[k]) { previous = k; continue; }
            int next = k + 1;
            while (next < count && glitch[next]) next++;
            if (previous < 0 || next == count) continue;
            cleaned[k] = speed[previous] + (speed[next] - speed[previous]) * (time[k] - time[previous]) / (time[next] - time[previous]);
        }
        return cleaned;
    }

    private static double median(double[] values, int index) {
        int from = Math.max(0, index - 1), to = Math.min(values.length, index + 2);
        List<Double> window = new ArrayList<>();
        for (int i = from; i < to; i++) if (!Double.isNaN(values[i])) window.add(values[i]);
        if (window.isEmpty()) return Double.NaN;
        Collections.sort(window);
        return window.get(window.size() / 2);
    }

    private static double percentile(List<Double> values, int percent) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, percent * sorted.size() / 100));
    }
}
