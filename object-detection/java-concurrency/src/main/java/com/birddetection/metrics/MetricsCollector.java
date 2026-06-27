package com.birddetection.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricsCollector — central registry for all pipeline metrics.
 *
 * Tracks the six metrics required by the assignment:
 *   1. Throughput       — images completed per second
 *   2. Latency          — p50 / p95 / p99 / avg / max per image (ms)
 *   3. Queue depth      — sampled every 5 s DURING the run + at shutdown
 *   4. Completed work   — images successfully post-processed
 *   5. Failed work      — images that errored after retry
 *   6. Rejected work    — images skipped by ValidationWorker (invalid files)
 *
 * Queue depth is now measured live using a ScheduledExecutorService that
 * prints all three queue sizes every 5 seconds during the run. This makes
 * overload and backpressure visible in real time, not only at shutdown.
 *
 * Latency percentiles (p50/p95/p99) are computed from all samples stored
 * in a CopyOnWriteArrayList — thread-safe for concurrent adds from 10 workers.
 *
 * All counters use AtomicInteger / AtomicLong — no locks needed.
 * printReport() is called once by MainApp after all latches clear.
 */
public class MetricsCollector {

    // Pipeline wall-clock start (set once, volatile for visibility)
    private volatile long pipelineStartMs = 0;

    // Work counters
    private final AtomicInteger completed = new AtomicInteger(0);
    private final AtomicInteger failed    = new AtomicInteger(0);
    private final AtomicInteger rejected  = new AtomicInteger(0);
    private final AtomicInteger retried   = new AtomicInteger(0);

    // Latency samples — full list for p50/p95/p99 computation
    // CopyOnWriteArrayList: thread-safe for concurrent add() from 10 workers
    private final List<Long> latencySamples = new CopyOnWriteArrayList<>();
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicLong maxLatencyMs   = new AtomicLong(0);

    // Queue depth at shutdown
    private volatile int rawQueueDepth       = 0;
    private volatile int validatedQueueDepth = 0;
    private volatile int resultQueueDepth    = 0;

    // Live queue sampler — runs every 5 s during the pipeline
    private ScheduledExecutorService sampler;

    // ---------------------------------------------------------------

    /** Called by MainApp before any worker starts. */
    public void start() {
        pipelineStartMs = System.currentTimeMillis();
    }

    /**
     * Starts live queue-depth sampling every 5 seconds.
     * Called by MainApp right after all workers are submitted.
     * Prints one line per sample so overload is visible during the run.
     * The sampler thread is a daemon — it never blocks JVM shutdown.
     */
    public void startQueueSampler(BlockingQueue<?> raw,
                                  BlockingQueue<?> validated,
                                  BlockingQueue<?> result) {
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QueueSampler");
            t.setDaemon(true);
            return t;
        });

        sampler.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - pipelineStartMs;
            System.out.printf(
                "[Queue Depth @%6d ms] raw=%-3d validated=%-3d result=%-3d  completed=%d%n",
                elapsed, raw.size(), validated.size(), result.size(), completed.get()
            );
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** Stops the live sampler. Called by MainApp before printReport(). */
    public void stopQueueSampler() {
        if (sampler != null) sampler.shutdownNow();
    }

    /** Called by DetectionWorker on successful Flask response. */
    public void recordCompleted(long latencyMs) {
        completed.incrementAndGet();
        latencySamples.add(latencyMs);
        totalLatencyMs.addAndGet(latencyMs);
        long cur;
        do { cur = maxLatencyMs.get(); }
        while (latencyMs > cur && !maxLatencyMs.compareAndSet(cur, latencyMs));
    }

    /** Called by DetectionWorker when both attempts fail. */
    public void recordFailed()   { failed.incrementAndGet(); }

    /** Called by ValidationWorker when a file is skipped (invalid). */
    public void recordRejected() { rejected.incrementAndGet(); }

    /** Called by DetectionWorker on first failure, before retry. */
    public void recordRetry()    { retried.incrementAndGet(); }

    /** Snapshot queue depths at shutdown (should all be 0). */
    public void snapshotQueueDepths(BlockingQueue<?> raw,
                                    BlockingQueue<?> validated,
                                    BlockingQueue<?> result) {
        rawQueueDepth       = raw.size();
        validatedQueueDepth = validated.size();
        resultQueueDepth    = result.size();
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** Prints the full metrics report. Called once by MainApp at the end. */
    public void printReport(long pipelineEndMs) {

        long   elapsedMs  = pipelineEndMs - pipelineStartMs;
        double elapsedSec = elapsedMs / 1000.0;
        int    total      = completed.get() + failed.get();
        double throughput = (elapsedSec > 0) ? completed.get() / elapsedSec : 0;

        int    n          = latencySamples.size();
        double avgLatency = (n > 0) ? (double) totalLatencyMs.get() / n : 0;
        long   maxLat     = maxLatencyMs.get();

        List<Long> sorted = new ArrayList<>(latencySamples);
        Collections.sort(sorted);

        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);

        System.out.println("\n========== PIPELINE METRICS ==========");
        System.out.println("  Pipeline duration     : " + elapsedMs + " ms");
        System.out.println("");
        System.out.println("  --- Throughput ---");
        System.out.printf ("  Images/sec (success)  : %.2f img/s%n", throughput);
        System.out.println("");
        System.out.println("  --- Latency (Flask round-trip per image) ---");
        System.out.printf ("  Average               : %.0f ms%n", avgLatency);
        System.out.println("  p50  (median)         : " + p50 + " ms");
        System.out.println("  p95                   : " + p95 + " ms");
        System.out.println("  p99                   : " + p99 + " ms");
        System.out.println("  Max                   : " + maxLat + " ms");
        System.out.println("  Samples               : " + n);
        System.out.println("");
        System.out.println("  --- Work accounting ---");
        System.out.println("  Completed (success)   : " + completed.get());
        System.out.println("  Failed (after retry)  : " + failed.get());
        System.out.println("  Rejected (invalid)    : " + rejected.get());
        System.out.println("  Retried (1st attempt) : " + retried.get());
        System.out.println("  Total submitted       : " + total);
        System.out.println("");
        System.out.println("  --- Queue depth at shutdown (all should be 0) ---");
        System.out.println("  rawQueue depth        : " + rawQueueDepth);
        System.out.println("  validatedQueue depth  : " + validatedQueueDepth);
        System.out.println("  resultQueue depth     : " + resultQueueDepth);
        System.out.println("======================================\n");
    }
}
