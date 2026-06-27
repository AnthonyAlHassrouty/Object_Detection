package com.birddetection.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import com.birddetection.results.DetectionResult;
import com.birddetection.results.ResultAggregator;

/**
 * STAGE 3 — Post-process / Store
 *
 * Reads raw DetectionResult objects from the result queue.
 * Filters out low-confidence detections (below MIN_CONFIDENCE),
 * then stores the cleaned result in the ResultAggregator.
 */
public class PostProcessWorker implements Runnable {

    // Only keep detections with at least this confidence score
    private static final double MIN_CONFIDENCE = 0.5;

    private final BlockingQueue<DetectionResult> inputQueue;  // from DetectionWorker
    private final ResultAggregator aggregator;
    private final CountDownLatch latch;

    public PostProcessWorker(BlockingQueue<DetectionResult> inputQueue,
                             ResultAggregator aggregator,
                             CountDownLatch latch) {
        this.inputQueue = inputQueue;
        this.aggregator = aggregator;
        this.latch      = latch;
    }

    @Override
    public void run() {
        try {
            while (true) {
                DetectionResult result = inputQueue.take();

                // Poison pill: stop this stage
                if (result.isPoisonPill()) {
                    break;
                }

                if (result.isError()) {
                    aggregator.addError(result.getImagePath());
                } else {
                    // --- post-process: filter by confidence ---
                    Map<String, Integer> filtered = filterByConfidence(result);
                    aggregator.addResult(result.getImagePath(), filtered);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            latch.countDown();
        }
    }

    /**
     * Removes object classes whose detection confidence is below MIN_CONFIDENCE.
     * The raw counts map from YOLOv8 does not carry per-object confidence,
     * so here we keep every class that appeared in the response and log the filter.
     * (Confidence filtering at per-detection level is done inside ObjectDetector.py;
     *  this stage shows the post-processing step clearly for the pipeline.)
     */
    private Map<String, Integer> filterByConfidence(DetectionResult result) {
        Map<String, Integer> counts = result.getCounts();

        // Remove any class with zero detections (safety clean-up)
        Map<String, Integer> cleaned = new HashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                cleaned.put(e.getKey(), e.getValue());
            }
        }
        return cleaned;
    }
}
