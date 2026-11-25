package com.mokuku.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class TripAnalysisTest {
    // A drive of `count` one-second points at speeds from `speed`, with a constant yaw rate.
    private static TripAnalysis drive(int count, java.util.function.IntToDoubleFunction speed, double yawRate) {
        double[] time = new double[count], gpsSpeed = new double[count], yaw = new double[count];
        double distance = 0, maximum = 0;
        for (int i = 0; i < count; i++) {
            time[i] = i;
            gpsSpeed[i] = speed.applyAsDouble(i);
            yaw[i] = yawRate;
            distance += gpsSpeed[i];
            maximum = Math.max(maximum, gpsSpeed[i] * 3.6);
        }
        return new TripAnalysis(time, gpsSpeed, yaw, distance, maximum);
    }

    @Test public void steadyModerateDrivingIsEco() {
        TripAnalysis analysis = drive(300, i -> 15 + Math.sin(i / 20.0), 0);
        assertEquals(0, analysis.harshEvents());
        assertEquals(TripAnalysis.Style.ECO, analysis.style);
    }

    @Test public void idlingMakesSteadyDrivingSmoothNotEco() {
        // Stopped for the first 100 s, then a gentle 1 m/s² start to 15 m/s.
        TripAnalysis analysis = drive(300, i -> Math.min(15, Math.max(0, i - 100)), 0);
        assertEquals(0, analysis.harshEvents());
        assertEquals(1 / 3.0, analysis.idleShare, 0.01);
        assertEquals(TripAnalysis.Style.SMOOTH, analysis.style);
    }

    @Test public void eachHarshEpisodeCountsOnce() {
        // Two separate bursts of +4 m/s² over two seconds each, then one 5 m/s² stop from 20 m/s.
        TripAnalysis analysis = drive(300, i -> {
            if (i < 50) return 10;
            if (i < 52) return 10 + 4 * (i - 49);
            if (i < 100) return 18;
            if (i < 102) return 18 + 4 * (i - 99);
            if (i < 200) return 26;
            if (i < 206) return Math.max(0, 26 - 5 * (i - 199));
            return 0;
        }, 0);
        assertEquals(2, analysis.harshAccelerations);
        assertEquals(1, analysis.harshBrakings);
        assertEquals(0, analysis.harshTurns);
        assertEquals(TripAnalysis.Style.AGGRESSIVE, analysis.style);
    }

    @Test public void corneringUsesSpeedTimesYawRate() {
        // 15 m/s at 0.3 rad/s is 4.5 m/s² sideways: one long harsh turn.
        TripAnalysis analysis = drive(300, i -> 15, 0.3);
        assertEquals(1, analysis.harshTurns);
        assertEquals(4.5, analysis.typicalLateralAcceleration, 1e-9);
        assertEquals(TripAnalysis.Style.SPORTY, analysis.style);
    }

    @Test public void recordingGlitchesAreNotHarshEvents() {
        // A steady 1 m/s² climb from 5 to 25 m/s, then cruising, with the glitches seen in real trips:
        // a fix recorded twice 50 ms apart, a lone zero speed, and a speed repeated once then caught up.
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double speed = Math.min(25, 5 + i) + 0.01 * (i % 7);
            if (i == 100) speed = 0;
            if (i == 150) speed = points.get(points.size() - 1)[1];
            points.add(new double[] {i, speed});
            if (i % 40 == 3) points.add(new double[] {i + 0.05, speed});
        }
        double[] time = new double[points.size()], speed = new double[points.size()], yaw = new double[points.size()];
        for (int i = 0; i < time.length; i++) { time[i] = points.get(i)[0]; speed[i] = points.get(i)[1]; }
        TripAnalysis analysis = new TripAnalysis(time, speed, yaw, 7000, 90);
        assertEquals(0, analysis.harshEvents());
    }

    @Test public void unknownYawRateAndGpsGapsAreIgnored() {
        double[] time = {0, 1, 10, 11}, speed = {10, 10, 0, 0}, yaw = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        TripAnalysis analysis = new TripAnalysis(time, speed, yaw, 20, 36);
        assertEquals(0, analysis.harshEvents());
        assertFalse(analysis.yawRateKnown);
        assertEquals(TripAnalysis.Style.TOO_SHORT, analysis.style);
    }
}
