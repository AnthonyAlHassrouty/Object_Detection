package com.birddetection.benchmark;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;

/**
 * Benchmark — Sequential vs Parallel image pre-processing
 *
 * The CPU-bound operation measured here is loading + reading image dimensions
 * for every image in the test folder.  This is the same work ValidationWorker
 * does in the real pipeline, so the comparison is meaningful.
 *
 * Run order:
 *   1. Sequential  – one image at a time on the calling thread
 *   2. Parallel    – images split across N threads (same count as pipeline)
 *
 * Results are printed to stdout so the teacher can see the speedup clearly.
 */
public class BenchmarkRunner {

    private final String imageFolder;
    private final int    numThreads;

    public BenchmarkRunner(String imageFolder, int numThreads) {
        this.imageFolder = imageFolder;
        this.numThreads  = numThreads;
    }

    public void run() throws Exception {

        // Collect all image files
        File folder = new File(imageFolder);
        File[] files = folder.listFiles();
        List<File> images = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                        || name.endsWith(".png") || name.endsWith(".bmp")) {
                    images.add(f);
                }
            }
        }

        if (images.isEmpty()) {
            System.out.println("[Benchmark] No images found in: " + imageFolder);
            return;
        }

        System.out.println("\n========== BENCHMARK: Sequential vs Parallel ==========");
        System.out.println("  Images   : " + images.size());
        System.out.println("  Threads  : " + numThreads);
        System.out.println("  Operation: load image + read dimensions (CPU-bound)");
        System.out.println("=======================================================");

        // --- 1. SEQUENTIAL ---
        long seqStart = System.currentTimeMillis();
        int seqCount  = 0;

        for (File img : images) {
            processImage(img);
            seqCount++;
        }

        long seqTime = System.currentTimeMillis() - seqStart;

        // --- 2. PARALLEL ---
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();

        long parStart = System.currentTimeMillis();

        for (File img : images) {
            futures.add(pool.submit(() -> {
                processImage(img);
            }));
        }

        // Wait for all parallel tasks to finish
        for (Future<?> f : futures) {
            f.get();
        }

        long parTime = System.currentTimeMillis() - parStart;
        pool.shutdown();

        // --- Print results ---
        double speedup = (parTime > 0) ? (double) seqTime / parTime : seqTime;

        System.out.println("\n--- Results ---");
        System.out.printf("  Sequential  : %d ms  (%d images)%n", seqTime, seqCount);
        System.out.printf("  Parallel    : %d ms  (%d threads)%n", parTime, numThreads);
        System.out.printf("  Speedup     : %.2fx%n", speedup);
        System.out.println("=======================================================\n");
    }

    /**
     * CPU-bound work: read the image file and access its pixel dimensions.
     * This forces the JVM to fully decode the image, not just open the file handle.
     */
    private void processImage(File img) {
        try {
            BufferedImage bi = ImageIO.read(img);
            if (bi != null) {
                // Access dimensions to ensure decode actually runs
                int w = bi.getWidth();
                int h = bi.getHeight();
            }
        } catch (Exception e) {
            // Ignore unreadable files in benchmark
        }
    }
}
