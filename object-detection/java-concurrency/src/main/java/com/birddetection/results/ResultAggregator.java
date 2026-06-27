package com.birddetection.results;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;  // FIX: thread-safe map
import java.util.concurrent.atomic.AtomicInteger;

public class ResultAggregator {

    // FIX: ConcurrentHashMap replaces HashMap — safe for concurrent addResult/addError calls
    private final Map<String, Map<String, Integer>> imageObjects = new ConcurrentHashMap<>();

    // total number of detected objects (all images combined)
    private final AtomicInteger totalObjects = new AtomicInteger(0);

    // number of failed images
    private final AtomicInteger errors = new AtomicInteger(0);


    public synchronized void addResult(String imageName, Map<String, Integer> objectsDetected) {

        imageObjects.put(imageName, objectsDetected);

        for (int count : objectsDetected.values()) {
            totalObjects.addAndGet(count);
        }
    }


    public synchronized void addError(String imageName) {
        imageObjects.put(imageName, null);
        errors.incrementAndGet();
    }


    public void printSummary() {

        System.out.println("\n===== OBJECT DETECTION RESULTS =====");

        int totalImages = imageObjects.size();
        int errorCount  = errors.get();
        int success     = totalImages - errorCount;

        System.out.println("Total images          : " + totalImages);
        System.out.println("Successful            : " + success);
        System.out.println("Errors                : " + errorCount);
        System.out.println("Total objects detected: " + totalObjects.get());

        System.out.println("\n--- Per Image ---");

        for (Map.Entry<String, Map<String, Integer>> entry : imageObjects.entrySet()) {

            System.out.println("\n" + entry.getKey());

            if (entry.getValue() == null) {
                System.out.println("  ERROR");
                continue;
            }

            if (entry.getValue().isEmpty()) {
                System.out.println("  No objects detected");
            }

            for (Map.Entry<String, Integer> obj : entry.getValue().entrySet()) {
                System.out.println("  " + obj.getKey() + " → " + obj.getValue());
            }
        }

        // Overall object summary
        Map<String, Integer> totalPerObject = new HashMap<>();

        for (Map<String, Integer> objects : imageObjects.values()) {
            if (objects == null) continue;
            for (Map.Entry<String, Integer> obj : objects.entrySet()) {
                totalPerObject.merge(obj.getKey(), obj.getValue(), Integer::sum);
            }
        }

        System.out.println("\n--- Overall Object Summary ---");

        for (Map.Entry<String, Integer> entry : totalPerObject.entrySet()) {
            System.out.println(entry.getKey() + " → " + entry.getValue());
        }

        System.out.println("====================================\n");
    }

    public int getTotalObjects() { return totalObjects.get(); }
    public int getErrorCount()   { return errors.get(); }
}
