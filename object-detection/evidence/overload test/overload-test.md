========== BENCHMARK: Sequential vs Parallel ==========
  Images   : 102
  Threads  : 10
  Operation: load image + read dimensions (CPU-bound)
=======================================================

--- Results ---
  Sequential  : 773 ms  (102 images)
  Parallel    : 112 ms  (10 threads)
  Speedup     : 6.90x
=======================================================

========== PIPELINE METRICS ==========
  Pipeline duration     : 526833 ms

  --- Throughput ---
  Images/sec (success)  : 0.19 img/s

  --- Latency (Flask round-trip per image) ---
  Average               : 49082 ms
  p50  (median)         : 51663 ms
  p95                   : 58159 ms
  p99                   : 58390 ms
  Max                   : 58540 ms
  Samples               : 102

  --- Work accounting ---
  Completed (success)   : 102
  Failed (after retry)  : 0
  Rejected (invalid)    : 0
  Retried (1st attempt) : 0
  Total submitted       : 102

  --- Queue depth at shutdown (all should be 0) ---
  rawQueue depth        : 0
  validatedQueue depth  : 0
  resultQueue depth     : 0
======================================


===== OBJECT DETECTION RESULTS =====
Total images          : 102
Successful            : 102
Errors                : 0
Total objects detected: 147

--- Overall Object Summary ---
zebra ? 24
horse ? 12
giraffe ? 12
person ? 12
cat ? 9
bird ? 12
cow ? 9
dog ? 30
elephant ? 9
sheep ? 15
carrot ? 3
====================================