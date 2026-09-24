package com.match3d.common;

/**
 * Waits of players matched recently whose rating fell in one band of 100, band
 * 25 holding 2500 to 2599. A total and a count rather than a mean, so bands
 * combine exactly.
 */
public record WaitBand(int band, double totalWaitSeconds, long seats) {

    public static final int WIDTH = 100;

    public static int of(int rating) {
        return rating / WIDTH;
    }
}
