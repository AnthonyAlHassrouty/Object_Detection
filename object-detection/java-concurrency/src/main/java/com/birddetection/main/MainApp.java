package com.birddetection.main;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.birddetection.benchmark.BenchmarkRunner;
import com.birddetection.metrics.MetricsCollector;
import com.birddetection.producer.FolderImageProducer;
import com.birddetection.results.DetectionResult;
import com.birddetection.results.ResultAggregator;
import com.birddetection.worker.DetectionWorker;
import com.birddetection.worker.PostProcessWorker;
import com.birddetection.worker.ValidationWorker;

/**
 * Entry point — wires the full 3-stage parallel pipeline.
 *
 * ── Thread-pool sizing rationale ──────────────────────────────────
 * numThreads = 10 per stage (30 total).
 *
 * Stage 1 (Validation) is CPU-bound (file I/O check).
 *   → Could match CPU cores, but 10 is fine; work is tiny per image.
 *
 * Stage 2 (Detection) is I/O-bound (HTTP call to Flask).
 *   → For I/O-bound work, threads >> cores is correct: each thread
 *     spends ~99% of its time blocked waiting for the network response.
 *     10 threads means 10 concurrent requests to Flask, saturating
 *     Flask's thread pool (also set to handle 10 concurrent threads).
 *
 * Stage 3 (Post-process) is CPU-bound (filter + map write).
 *   → Light work; 10 threads matches Stage 2 so results are consumed
 *     as fast as they are produced, keeping resultQueue shallow.
 *
 * Queue capacity = 50 per queue.
 *   → With 34 images total, capacity 50 is never the bottleneck.
 *     It is bounded to demonstrate intentional backpressure: if a
 *     downstream stage stalls, the upstream stage blocks on put()
 *     instead of growing without limit (unbounded queues rejected).
 * ──────────────────────────────────────────────────────────────────
 *
 * Pipeline flow:
 *   [FolderImageProducer]
 *           ↓  rawQueue (cap 50)
 *   [ValidationWorker x10]   Stage 1: validate / pre-process
 *           ↓  validatedQueue (cap 50)
 *   [DetectionWorker x10]    Stage 2: infer / analyze (HTTP → Flask)
 *           ↓  resultQueue (cap 50)
 *   [PostProcessWorker x10]  Stage 3: post-process / store
 *           ↓
 *   [ResultAggregator]  +  [MetricsCollector]
 */
public class MainApp {

    public static void main(String[] args) throws Exception {

        final String imageFolder = "test-images";
        final String apiUrl      = "http://localhost:8080";
        final int    numThreads  = 10;   // see sizing rationale above

        // ── 0. BENCHMARK ─────────────────────────────────────────
        new BenchmarkRunner(imageFolder, numThreads).run();

        // ── 1. METRICS ───────────────────────────────────────────
        MetricsCollector metrics = new MetricsCollector();

        // ── 2. BOUNDED QUEUES (capacity 50 each) ─────────────────
        BlockingQueue<String>          rawQueue       = new LinkedBlockingQueue<>(50);
        BlockingQueue<String>          validatedQueue = new LinkedBlockingQueue<>(50);
        BlockingQueue<DetectionResult> resultQueue    = new LinkedBlockingQueue<>(50);

        // ── 3. RESULT STORE ──────────────────────────────────────
        ResultAggregator aggregator = new ResultAggregator();

        // ── 4. LATCHES (one per stage + producer) ────────────────
        CountDownLatch producerLatch    = new CountDownLatch(1);
        CountDownLatch validationLatch  = new CountDownLatch(numThreads);
        CountDownLatch detectionLatch   = new CountDownLatch(numThreads);
        CountDownLatch postProcessLatch = new CountDownLatch(numThreads);

        // ── 5. RACE-FREE SHUTDOWN COUNTERS ───────────────────────
        AtomicInteger validatorsLeft = new AtomicInteger(numThreads);
        AtomicInteger detectorsLeft  = new AtomicInteger(numThreads);

        // ── 6. PRODUCER ──────────────────────────────────────────
        FolderImageProducer producer =
                new FolderImageProducer(imageFolder, rawQueue, numThreads, producerLatch);
        new Thread(producer, "Producer").start();

        // ── 7. SHARED THREAD POOL (30 threads = 10 per stage) ───
        ExecutorService pool = Executors.newFixedThreadPool(numThreads * 3);

        // ── 8. STAGE 1 — Validation ──────────────────────────────
        for (int i = 0; i < numThreads; i++) {
            pool.submit(new ValidationWorker(
                    rawQueue, validatedQueue, validationLatch,
                    validatorsLeft, numThreads, metrics));
        }

        // ── 9. STAGE 2 — Detection ───────────────────────────────
        metrics.start();   // start timing AFTER benchmark, at pipeline launch
        for (int i = 0; i < numThreads; i++) {
            pool.submit(new DetectionWorker(
                    validatedQueue, resultQueue, apiUrl,
                    detectionLatch, detectorsLeft, numThreads, metrics));
        }

        // ── 10. STAGE 3 — Post-process ───────────────────────────
        for (int i = 0; i < numThreads; i++) {
            pool.submit(new PostProcessWorker(
                    resultQueue, aggregator, postProcessLatch));
        }

        // ── 10b. LIVE QUEUE DEPTH SAMPLER ────────────────────────
        // Prints raw/validated/result queue sizes every 5 s during the run.
        // Makes backpressure visible in real time (not only at shutdown).
        metrics.startQueueSampler(rawQueue, validatedQueue, resultQueue);

        // ── 11. WAIT FOR EVERY STAGE ─────────────────────────────
        producerLatch.await();
        validationLatch.await();
        detectionLatch.await();
        postProcessLatch.await();

        long pipelineEndMs = System.currentTimeMillis();

        // ── 12. GRACEFUL SHUTDOWN ────────────────────────────────
        pool.shutdown();
        // Wait up to 30 s for in-flight tasks to finish before forcing stop
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            System.err.println("[Shutdown] Pool did not terminate cleanly — forcing stop.");
            pool.shutdownNow();
        }

        // ── 13. METRICS REPORT ───────────────────────────────────
        metrics.stopQueueSampler();
        metrics.snapshotQueueDepths(rawQueue, validatedQueue, resultQueue);
        metrics.printReport(pipelineEndMs);

        // ── 14. DETECTION RESULTS ────────────────────────────────
        aggregator.printSummary();
    }
}
