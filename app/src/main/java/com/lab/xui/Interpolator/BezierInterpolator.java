package com.lab.xui.Interpolator;

import android.view.animation.Interpolator;

public class BezierInterpolator implements Interpolator {
    private double[] controlPoints = {0.0, 0.1, 0.3, 0.5, 0.85, 0.94, 1.0};
    // Shared per thread, not per animator or per frame. The calculation calls no user code,
    // so the scratch array cannot be re-entered while it is in use.
    private static final ThreadLocal<double[]> WORK = ThreadLocal.withInitial(() -> new double[64]);

    private static double evaluate(double t, double[] points) {
        if (points == null || points.length == 0) return 0.0;
        int count = points.length;
        if (count == 1) return points[0];
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        double[] work = WORK.get();
        if (work.length < count) {
            work = new double[count];
            WORK.set(work);
        }
        System.arraycopy(points, 0, work, 0, count);
        // Preserve the native De Casteljau operation order and every original control point.
        // In particular, overshooting control points are not clamped or approximated.
        for (int r = 1; r < count; r++) {
            for (int i = 0; i < count - r; i++) {
                work[i] = (1.0 - t) * work[i] + t * work[i + 1];
            }
        }
        return work[0];
    }

    public static class QuinticBezier {
        public static double bezier(double t, double[] controlPoints) {
            return evaluate(t, controlPoints);
        }
    }

    public void control(double[] controlPoints) {
        this.controlPoints = controlPoints;
    }

    @Override
    public float getInterpolation(float input) {
        return (float) evaluate(input, this.controlPoints);
    }
}
