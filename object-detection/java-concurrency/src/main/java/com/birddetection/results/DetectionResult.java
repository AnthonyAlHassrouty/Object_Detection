package com.birddetection.results;

import java.util.Collections;
import java.util.Map;

/**
 * Data object passed from Stage 2 (DetectionWorker) → Stage 3 (PostProcessWorker).
 * Carries either a successful result (imagePath + counts) or an error flag.
 * Also used as a poison pill to shut down Stage 3.
 */
public class DetectionResult {

    // Singleton poison pill instance
    public static final DetectionResult POISON = new DetectionResult();

    private final String imagePath;
    private final Map<String, Integer> counts;
    private final boolean error;
    private final boolean poisonPill;

    // --- constructors ---

    /** Poison pill */
    private DetectionResult() {
        this.imagePath  = null;
        this.counts     = null;
        this.error      = false;
        this.poisonPill = true;
    }

    /** Successful detection */
    public DetectionResult(String imagePath, Map<String, Integer> counts) {
        this.imagePath  = imagePath;
        this.counts     = counts;
        this.error      = false;
        this.poisonPill = false;
    }

    /** Failed detection */
    public DetectionResult(String imagePath) {
        this.imagePath  = imagePath;
        this.counts     = Collections.emptyMap();
        this.error      = true;
        this.poisonPill = false;
    }

    // --- getters ---

    public String getImagePath()           { return imagePath; }
    public Map<String, Integer> getCounts(){ return counts; }
    public boolean isError()               { return error; }
    public boolean isPoisonPill()          { return poisonPill; }
}
