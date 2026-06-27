========== BENCHMARK: Sequential vs Parallel ==========
  Images   : 34
  Threads  : 10
  Operation: load image + read dimensions (CPU-bound)
=======================================================

--- Results ---
  Sequential  : 330 ms  (34 images)
  Parallel    : 50 ms  (10 threads)
  Speedup     : 6.60x
=======================================================

========== PIPELINE METRICS ==========
  Pipeline duration     : 160970 ms

  --- Throughput ---
  Images/sec (success)  : 0.21 img/s

  --- Latency (Flask round-trip per image) ---
  Average               : 40526 ms
  p50  (median)         : 42302 ms
  p95                   : 52405 ms
  p99                   : 52797 ms
  Max                   : 52797 ms
  Samples               : 34

  --- Work accounting ---
  Completed (success)   : 34
  Failed (after retry)  : 0
  Rejected (invalid)    : 0
  Retried (1st attempt) : 9
  Total submitted       : 34

  --- Queue depth at shutdown (all should be 0) ---
  rawQueue depth        : 0
  validatedQueue depth  : 0
  resultQueue depth     : 0
======================================