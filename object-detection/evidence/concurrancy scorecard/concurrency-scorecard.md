# Concurrency Scorecard

| Question | Mechanism used | Evidence |
|---|---|---|
| **Thread-safe?** | `ConcurrentHashMap` in `ResultAggregator` for per-image results. `AtomicInteger` / `AtomicLong` in `MetricsCollector` for all counters. `synchronized` on `addResult()` and `addError()` in `ResultAggregator`. `threading.Lock()` in `flask_app.py` protecting the shared `stats` dictionary. | No raw `HashMap` or unprotected `+=` on any shared field. Multiple Stage 3 workers write simultaneously — zero corruption observed in stress test (queue depth 0, total images = completed + failed + rejected). |


| **Visibility guaranteed?** | `AtomicInteger` and `AtomicLong` use `volatile` internally — every `get()` sees the latest write from any thread (happens-before guarantee). `volatile long pipelineStartMs` in `MetricsCollector`. `ConcurrentHashMap` guarantees visibility of all puts to subsequent gets across threads. | Metrics report printed by MainApp after all latches clear always shows correct totals — no stale reads observed. |


| **Deadlock-free?** | Only one lock is ever held at a time: `synchronized` on `ResultAggregator` methods, `threading.Lock` in Flask — never nested. `BlockingQueue.put()` and `take()` can block but never while holding a lock. No circular wait is possible: each stage only ever waits on one queue. | No deadlock observed across all test runs. Lock acquisition is always single and short. Static analysis: no method acquires two locks simultaneously. |
| **Liveness guaranteed?** | Poison pill pattern guarantees every worker receives exactly one stop signal and exits its loop. `CountDownLatch.await()` in MainApp unblocks as soon as all workers call `countDown()`. `pool.awaitTermination(30s)` followed by `shutdownNow()` ensures MainApp never waits forever. Timeout + retry in `DetectionWorker` prevents any worker from blocking indefinitely on a slow Flask call. | Every run terminates. No thread ever busy-waits. Failure injection test (Flask down) still terminates cleanly with all 34 images accounted for. |


| **Bounded resources?** | Thread pool: `Executors.newFixedThreadPool(30)` — hard ceiling of 30 threads, never exceeded. Queues: all three queues are `LinkedBlockingQueue(50)` — fixed capacity, `put()` blocks when full (backpressure). No `newCachedThreadPool()`, no `new LinkedBlockingQueue()` without capacity anywhere. | `MainApp` line: `Executors.newFixedThreadPool(numThreads * 3)`. Queue declarations: `new LinkedBlockingQueue<>(50)`. Queue depth at shutdown = 0 (nothing accumulated). |


| **Failure recovery path?** | `DetectionWorker` catches `IOException` on HTTP call, logs `[Detection] Retrying:`, retries once. If retry succeeds → `metrics.recordCompleted()`, image processed normally. If retry fails → `metrics.recordFailed()`, `DetectionResult(error)` forwarded to Stage 3, `aggregator.addError()` called. Pipeline never stops — remaining images continue processing. `ValidationWorker` skips invalid files (`metrics.recordRejected()`) without stopping the pipeline. | Failure injection test (Flask down): all 34 images log `Retrying` then `ERROR after retry`, metrics show `Failed: 34`, pipeline still terminates cleanly. Partial failure test (Flask killed mid-run): completed images show in results, failed ones show as errors — no crash, no hang. |