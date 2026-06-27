package com.birddetection.producer;

import java.io.File;
import java.util.concurrent.BlockingQueue;  // make a thread safe queue (a producer that puts items and a consumer that takes them)
import java.util.concurrent.CountDownLatch; // to synchronize the threads

public class FolderImageProducer implements Runnable {  // runnabke means this class can run in thread

    public static final String POISON_PILL = "__STOP__";

    private final BlockingQueue<String> queue;  // shared dtorage between producer and consumer
    private final File folder; // image folder path
    private final int workers; // number of worker threads
    private final CountDownLatch doneSignal; // tell system producer is finished

    public FolderImageProducer(String folderPath,   //constructor
                               BlockingQueue<String> queue,
                               int workers,
                               CountDownLatch doneSignal) {

        this.folder = new File(folderPath);
        this.queue = queue;
        this.workers = workers;
        this.doneSignal = doneSignal;

        if (!folder.exists() || !folder.isDirectory()) {
            throw new IllegalArgumentException("Invalid folder: " + folderPath);
        }
    }

    @Override
    public void run() {
        try {
            File[] files = folder.listFiles();  // gets everything inside the folder

            if (files != null) {
                for (File file : files) {

                    if (isImage(file)) {
                        queue.put(file.getAbsolutePath());  // if image, send image path to worker
                    }
                }
            }

            // stop all workers
            for (int i = 0; i < workers; i++) {
                queue.put(POISON_PILL); // it is used to stop worker
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneSignal.countDown();
        }
    }

    private boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") ||
               name.endsWith(".jpeg") ||
               name.endsWith(".png") ||
               name.endsWith(".bmp");
    }
}

// this function take the images from a folder then send their path to a shared queue, then tell all worker thread to stop