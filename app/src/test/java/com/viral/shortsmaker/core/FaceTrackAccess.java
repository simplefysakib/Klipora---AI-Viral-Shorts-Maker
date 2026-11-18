package com.viral.shortsmaker.core;

import java.util.List;

/** v22 tests: the package-private split-geometry helpers */
public final class FaceTrackAccess {
    private FaceTrackAccess() {}
    public static double[] freeSpan(double[] f, List<double[]> people) { return FaceTrack.freeSpan(f, people); }
    public static double place(double[] f, double[] span, double pw) { return FaceTrack.place(f, span, pw); }
    public static double lxOf(double c, double w) { return FaceTrack.lxOf(c, w); }
}
