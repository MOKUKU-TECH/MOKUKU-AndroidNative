package com.mokuku.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadProgressTest {
    @Test public void waitsForSizeAndEnoughDataToEstimate() {
        assertEquals("Waiting for device reply…", Ui.downloadProgressText(0, 0, 1000));
        assertEquals("0 / 4096 bytes\nEstimating speed and time remaining…", Ui.downloadProgressText(0, 4096, 1000));
        assertEquals("1024 / 4096 bytes\nEstimating speed and time remaining…", Ui.downloadProgressText(1024, 4096, 999));
    }

    @Test public void calculatesAverageSpeedAndRoundsRemainingTimeUp() {
        assertEquals("1024 / 4096 bytes\nAverage speed: 1.0 KiB/s\nAbout 3s left", Ui.downloadProgressText(1024, 4096, 1000));
        assertTrue(Ui.downloadProgressText(1024, 2049, 1000).endsWith("About 2s left"));
        assertEquals("1024 / 4096 bytes\nAverage speed: 512.0 B/s\nAbout 6s left", Ui.downloadProgressText(1024, 4096, 2000));
    }

    @Test public void formatsLargeRatesAndLongTransfers() {
        assertTrue(Ui.downloadProgressText(1048576, 5242880, 1000).contains("Average speed: 1.0 MiB/s"));
        assertTrue(Ui.downloadProgressText(128, 8064, 1000).endsWith("About 1m 2s left"));
        assertTrue(Ui.downloadProgressText(1, 3662, 1000).endsWith("About 1h 1m 1s left"));
    }

    @Test public void showsFinishingAfterAllBytesArrive() {
        assertEquals("2048 / 2048 bytes\nFinishing download…", Ui.downloadProgressText(2048, 2048, 2000));
    }
}
