package com.birddetection.worker;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.birddetection.metrics.MetricsCollector;
import com.birddetection.producer.FolderImageProducer;

/**
 * STAGE 1 — Validate / Pre-process
 *
 * Checks each file: exists, readable, valid extension.
 * Valid paths → validatedQueue (Stage 2).
 * Invalid paths → logged + counted as REJECTED in MetricsCollector.
 *
 * SHUTDOWN (race-free): only the last validator (AtomicInteger hits 0)
 * injects poison pills for Stage 2, guaranteeing no real item is lost.
 */
public class ValidationWorker implements Runnable {

    private final BlockingQueue<String> inputQueue;
    private final BlockingQueue<String> outputQueue;
    private final CountDownLatch        latch;
    private final AtomicInteger         remainingWorkers;
    private final int                   nextStageWorkers;
    private final MetricsCollector      metrics;

    public ValidationWorker(BlockingQueue<String> inputQueue,
                            BlockingQueue<String> outputQueue,
                            CountDownLatch        latch,
                            AtomicInteger         remainingWorkers,
                            int                   nextStageWorkers,
                            MetricsCollector      metrics) {
        this.inputQueue       = inputQueue;
        this.outputQueue      = outputQueue;
        this.latch            = latch;
        this.remainingWorkers = remainingWorkers;
        this.nextStageWorkers = nextStageWorkers;
        this.metrics          = metrics;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String imagePath = inputQueue.take();

                if (imagePath.equals(FolderImageProducer.POISON_PILL)) {
                    if (remainingWorkers.decrementAndGet() == 0) {
                        for (int i = 0; i < nextStageWorkers; i++) {
                            outputQueue.put(FolderImageProducer.POISON_PILL);
                        }
                    }
                    break;
                }

                if (isValid(imagePath)) {
                    outputQueue.put(imagePath);
                } else {
                    System.out.println("[Validation] SKIPPED (invalid): " + imagePath);
                    metrics.recordRejected();   // METRIC: rejected/delayed work
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            latch.countDown();
        }
    }

    private boolean isValid(String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead()) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".png") || name.endsWith(".bmp");
    }
}
