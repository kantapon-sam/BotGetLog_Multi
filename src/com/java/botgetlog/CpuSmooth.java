package com.java.botgetlog;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

/**
 * CPU smoother similar to Task Manager behavior.
 * - EWMA rolling average
 * - Anti-spike limiter for per-second changes
 * - Fallback when getProcessCpuLoad() returns -1:
 *   1) use getSystemCpuLoad()
 *   2) if that is unavailable, use processCpuTime delta / cores
 */
public class CpuSmooth {

    private double ewma = 0.0;      // smoothed (%)
    private double lastOut = 0.0;   // last output (%)
    private long lastTs = System.nanoTime();

    private long lastProcCpuTimeNs = -1;

    private final double alpha;            // 0.15-0.22 for smooth Task Manager style output
    private final double maxChangePerSec;  // 18-30 percent per second

    public CpuSmooth(double alpha, double maxChangePerSec) {
        this.alpha = alpha;
        this.maxChangePerSec = maxChangePerSec;
    }

    /** Accepts a load value in the range 0..1, or -1 when unavailable. */
    public synchronized double updateFromLoad(double load01) {
        return smooth(load01);
    }

    /**
     * Recommended path: read from the OS bean and let this class apply fallbacks automatically.
     * @param os com.sun.management.OperatingSystemMXBean
     * @param preferProcess true to try process CPU first, false to try system CPU first
     */
    public synchronized double updateFromOs(OperatingSystemMXBean os, boolean preferProcess) {
        double load = -1;

        if (os != null) {
            if (preferProcess) {
                load = safe01(os.getProcessCpuLoad());
                if (load < 0) {
                    load = safe01(os.getSystemCpuLoad());
                }
            } else {
                load = safe01(os.getSystemCpuLoad());
                if (load < 0) {
                    load = safe01(os.getProcessCpuLoad());
                }
            }

            // Final fallback: processCpuTime delta.
            if (load < 0) {
                load = computeFromProcessCpuTime(os);
            }
        }

        return smooth(load);
    }

    /** Convenience helper that fetches the OS bean automatically. */
    public synchronized double updateAuto(boolean preferProcess) {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return updateFromOs(os, preferProcess);
        } catch (Exception e) {
            return lastOut;
        }
    }

    private double smooth(double load01) {
        long now = System.nanoTime();
        double dt = (now - lastTs) / 1_000_000_000.0;
        if (dt <= 0) {
            dt = 0.001;
        }
        lastTs = now;

        // -1 means unavailable, so keep the previous value to avoid flicker.
        if (load01 < 0) {
            return lastOut;
        }

        double raw = load01 * 100.0;
        if (raw < 0) {
            raw = 0;
        }
        if (raw > 100) {
            raw = 100;
        }

        // EWMA smoothing.
        ewma = (ewma == 0.0) ? raw : (ewma * (1.0 - alpha) + raw * alpha);

        // Clamp changes based on elapsed time.
        double maxDelta = maxChangePerSec * dt;
        double delta = ewma - lastOut;

        if (delta > maxDelta) {
            delta = maxDelta;
        }
        if (delta < -maxDelta) {
            delta = -maxDelta;
        }

        lastOut = lastOut + delta;

        if (Double.isNaN(lastOut) || Double.isInfinite(lastOut)) {
            lastOut = 0.0;
        }

        return lastOut;
    }

    private double safe01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return -1;
        }
        // The API normally returns 0..1 or -1.
        if (v < 0) {
            return -1;
        }
        if (v > 1) {
            v = 1;
        }
        return v;
    }

    private double computeFromProcessCpuTime(OperatingSystemMXBean os) {
        try {
            long procNs = os.getProcessCpuTime(); // nanoseconds, may return -1
            if (procNs <= 0) {
                return -1;
            }

            long now = System.nanoTime();
            double dt = (now - lastTs) / 1_000_000_000.0;
            if (dt <= 0) {
                dt = 0.001;
            }

            if (lastProcCpuTimeNs < 0) {
                lastProcCpuTimeNs = procNs;
                return -1; // Need one prior sample before a delta can be calculated.
            }

            long deltaProc = procNs - lastProcCpuTimeNs;
            lastProcCpuTimeNs = procNs;

            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double usage01 = (deltaProc / 1_000_000_000.0) / (dt * cores);

            if (Double.isNaN(usage01) || Double.isInfinite(usage01)) {
                return -1;
            }
            if (usage01 < 0) {
                usage01 = 0;
            }
            if (usage01 > 1) {
                usage01 = 1;
            }
            return usage01;
        } catch (Exception e) {
            return -1;
        }
    }
}

