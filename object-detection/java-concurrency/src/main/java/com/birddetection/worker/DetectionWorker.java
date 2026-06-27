package com.birddetection.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.birddetection.metrics.MetricsCollector;
import com.birddetection.producer.FolderImageProducer;
import com.birddetection.results.DetectionResult;

/**
 * STAGE 2 — Infer / Analyze
 *
 * Sends each validated image path to the Flask/YOLOv8 HTTP API.
 * Records per-image latency, retries, and failures into MetricsCollector.
 *
 * Timeouts:
 *   - Connect: 5 s  (local server; fast fail if Flask is down)
 *   - Read:    5 min (yolov8x on CPU with 10 concurrent requests is slow)
 *
 * Retry with exponential backoff:
 *   Attempt 1: immediate
 *   Attempt 2: waits RETRY_BACKOFF_MS (1 s) before retrying
 *   If attempt 2 fails → permanent failure recorded in metrics
 *   Backoff prevents thundering-herd when Flask is temporarily overloaded.
 *
 * Request tracing:
 *   A UUID traceId is generated per image and sent in the JSON body.
 *   Flask echoes it in its log [TRACE traceId], linking Java and Python
 *   log lines for the same request across the network boundary.
 *
 * Shutdown: race-free — only the last worker injects Stage 3 poison pills.
 */
public class DetectionWorker implements Runnable {

    private static final int  CONNECT_TIMEOUT_MS = 5_000;
    private static final int  READ_TIMEOUT_MS    = 300_000;
    private static final long RETRY_BACKOFF_MS   = 1_000;  // 1 s before retry

    private final BlockingQueue<String>          inputQueue;
    private final BlockingQueue<DetectionResult> outputQueue;
    private final String                         apiUrl;
    private final CountDownLatch                 latch;
    private final AtomicInteger                  remainingWorkers;
    private final int                            nextStageWorkers;
    private final MetricsCollector               metrics;

    public DetectionWorker(BlockingQueue<String>          inputQueue,
                           BlockingQueue<DetectionResult> outputQueue,
                           String                         apiUrl,
                           CountDownLatch                 latch,
                           AtomicInteger                  remainingWorkers,
                           int                            nextStageWorkers,
                           MetricsCollector               metrics) {
        this.inputQueue       = inputQueue;
        this.outputQueue      = outputQueue;
        this.apiUrl           = apiUrl;
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
                            outputQueue.put(DetectionResult.POISON);
                        }
                    }
                    break;
                }

                // Generate a unique trace ID for this image.
                // Sent to Flask in the JSON body so both Java and Python
                // log lines share the same ID — cross-component tracing.
                String traceId = UUID.randomUUID().toString().substring(0, 8);

                long start = System.currentTimeMillis();

                try {
                    String json = sendToAPI(imagePath, traceId);
                    Map<String, Integer> counts = parseCounts(json);
                    long latency = System.currentTimeMillis() - start;

                    metrics.recordCompleted(latency);
                    System.out.println("[TRACE " + traceId + "] OK (" + latency + " ms): " + imagePath);
                    outputQueue.put(new DetectionResult(imagePath, counts));

                } catch (Exception e) {

                    // FALLBACK: exponential backoff then one retry.
                    // Waiting 1 s before retrying avoids thundering-herd:
                    // if Flask is momentarily overloaded, 10 threads
                    // retrying instantly would make the problem worse.
                    metrics.recordRetry();
                    System.out.println("[TRACE " + traceId + "] RETRY (backoff "
                            + RETRY_BACKOFF_MS + " ms): " + imagePath);

                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    try {
                        String json = sendToAPI(imagePath, traceId);
                        Map<String, Integer> counts = parseCounts(json);
                        long latency = System.currentTimeMillis() - start;

                        metrics.recordCompleted(latency);
                        System.out.println("[TRACE " + traceId + "] OK after retry ("
                                + latency + " ms): " + imagePath);
                        outputQueue.put(new DetectionResult(imagePath, counts));

                    } catch (Exception ex) {
                        metrics.recordFailed();
                        System.out.println("[TRACE " + traceId + "] ERROR after retry: " + imagePath);
                        outputQueue.put(new DetectionResult(imagePath));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            latch.countDown();
        }
    }

    // traceId is included in the JSON body so Flask can log it
    private String sendToAPI(String imagePath, String traceId) throws IOException {
        URL url = new URL(apiUrl + "/api/detect");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        String body = "{\"image_path\":\"" + imagePath.replace("\\", "\\\\")
                    + "\",\"trace_id\":\"" + traceId + "\"}";

        try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes()); }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private Map<String, Integer> parseCounts(String json) {
        Map<String, Integer> map = new HashMap<>();
        try {
            String section = json.split("\"counts\"")[1];
            section = section.substring(section.indexOf("{") + 1, section.indexOf("}"));
            for (String item : section.split(",")) {
                String[] kv = item.replace("\"", "").split(":");
                if (kv.length == 2) map.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
            }
        } catch (Exception ignored) {}
        return map;
    }
}
