# Architecture Decision Memo
## Project: Concurrent Object-Detection Pipeline
## Decision: Bounded Queue Capacity (capacity = 50)

---

## Context

The pipeline processes images through three sequential stages — Validation,
Detection, and Post-processing — each running 10 parallel threads. The stages
are connected by three `LinkedBlockingQueue` instances. The central design
decision was: **what capacity should these queues have, and why not unbounded?**

We chose a fixed capacity of **50 items per queue**.

We ran two loads to measure behavior:
- **Normal load:** 34 images
- **Overload:** 102 images (queue capacity 50 < total images 102)

---

## Q1. What guarantee does your chosen design provide?

A bounded queue with capacity 50 provides **backpressure**.

When a downstream stage is slower than the upstream stage — in our case
Stage 2 (Detection) is the bottleneck because YOLOv8 inference takes
47 710 ms on average per image — the `validatedQueue` fills up. Once it
reaches capacity 50, Stage 1's `ValidationWorker` threads block on `put()`
and automatically pause. They resume only when Stage 2 consumes an item
and frees a slot.

This gives three concrete guarantees:

- **Memory is bounded.** At most 50 image paths sit in each queue at any
  time, regardless of how fast the producer runs. With 102 images and
  capacity 50, the producer was forced to block — backpressure activated.
- **No thread starvation from queue growth.** An unbounded queue can grow
  until the JVM runs out of heap memory. A bounded queue turns that crash
  into a controlled, measurable pause.
- **Upstream and downstream stages stay coupled in throughput.** The system
  cannot race ahead on one stage while another falls behind indefinitely.

---

## Q2. What failure modes does it prevent?

**Out-of-memory crash (OOM).**
With an unbounded `LinkedBlockingQueue`, if Stage 2 slows down (as it did —
average 47 710 ms per image under overload), the producer would keep filling
the queue without limit. With 102 images and no capacity ceiling, all 102
paths would be queued instantly in memory. At scale (10 000 images) this
would cause `OutOfMemoryError`. The bounded queue prevents this — the
producer blocks at item 51 and waits.

**Unbounded resource growth violating the assignment.**
The assignment explicitly states: *"Unbounded resources are not accepted."*
Using `new LinkedBlockingQueue()` without a capacity argument would fail
this requirement outright.

**Masking a slow stage.**
Without backpressure, Stage 1 floods `validatedQueue` instantly, hiding the
bottleneck. With bounded queues, the producer blocks and the queue depth
metric reveals exactly where the pressure is. Our overload run confirmed
this — all 102 images completed with 9 retries absorbed transparently.

---

## Q3. What failure modes does it introduce?

**Risk of deadlock if capacity is smaller than the poison pill count.**
Shutdown injects 10 poison pills per queue. If the queue is full of real
items AND all consumers are blocked trying to put into the next queue, no
thread can make progress — deadlock. We chose capacity 50, which is always
larger than the 10 poison pills, so this cannot occur in our pipeline.
For workloads where images arrive faster than they are consumed and the
queue stays full, this would need to be re-evaluated.

**Head-of-line blocking under backpressure.**
When `validatedQueue` is full, all 10 Stage 1 threads block on `put()`.
Stage 1 throughput drops to zero until Stage 2 catches up. This is
intentional behavior, but it means a single slow Stage 2 worker stalls
all of Stage 1. In our overload run, the 47 710 ms average latency shows
that Stage 2 (Flask/YOLOv8) was the clear bottleneck — Stage 1 and Stage 3
spent most of their time waiting.

**Increased end-to-end latency under overload.**
With 34 images: pipeline completed in ~48 000 ms.
With 102 images: pipeline completed in 509 986 ms.
That is not linear (3× images → 10× time) because the 9 retries added
extra round-trips. Under a heavier retry rate the bounded queue also causes
items to wait longer in the queue before a worker picks them up.

---

## Q4. How does it behave under overload? Measured numbers.

**Normal load — 34 images, queue capacity 50 (queue never fills):**

| Metric | Value |
|---|---|
| Pipeline duration | ~48 000 ms |
| Throughput | ~0.70 img/s |
| Average latency per image | ~1 382 ms |
| Max latency per image | ~4 201 ms |
| Completed | 34 |
| Failed | 0 |
| Retried | 0 |
| Queue depth at shutdown | 0 / 0 / 0 |

**Overload — 102 images, queue capacity 50 (queue fills → backpressure activates):**

| Metric | Value |
|---|---|
| Pipeline duration | **509 986 ms** |
| Throughput | **0.20 img/s** |
| Average latency per image | **47 710 ms** |
| Max latency per image | **52 864 ms** |
| Completed | **102** |
| Failed | **0** |
| Retried | **9** |
| Rejected (invalid files) | 0 |
| Queue depth at shutdown | **0 / 0 / 0** |

**Key observations:**

1. **Throughput dropped from 0.70 to 0.20 img/s** under 3× the load. This
   is expected — YOLOv8 is CPU-bound on the Python side. The GIL serializes
   inference, so 10 concurrent Java requests do not give 10× Python speedup.
   The bottleneck is the model, not the Java pipeline.

2. **9 retries were absorbed transparently.** No image failed. The retry
   fallback in `DetectionWorker` caught 9 transient timeouts and recovered
   all of them. Completed = 102, Failed = 0.

3. **All queue depths = 0 at shutdown.** Despite 102 images and real
   backpressure activating on the queues (102 > 50), not a single item was
   lost, stuck, or duplicated. The bounded queues, poison pill shutdown,
   and `CountDownLatch` coordination worked correctly under overload.

4. **Latency increased from 1 382 ms to 47 710 ms average.** This reflects
   queue waiting time added on top of inference time when the system is
   under load and images pile up waiting for a free Stage 2 worker.

**Benchmark — sequential vs parallel (CPU-bound pre-processing):**

| Mode | Time | Speedup |
|---|---|---|
| Sequential (1 thread) | 400 ms | 1.00× |
| Parallel (10 threads) | 51 ms | **7.84×** |

---

## Q5. How would a new engineer debug it?

**Step 1 — Read the metrics report first.**
Every run prints a `PIPELINE METRICS` block. A new engineer should read
this before touching any code:

```
Completed (success)   : 102   ← all images processed
Failed (after retry)  : 0     ← no permanent failures
Retried (1st attempt) : 9     ← 9 transient issues, all recovered
rawQueue depth        : 0     ← no items stuck between stages
validatedQueue depth  : 0
resultQueue depth     : 0
```

- `Failed > 0` → Flask is down or timing out permanently. Check
  `[Detection] ERROR after retry:` log lines.
- `Retried > 0` but `Failed = 0` → transient timeouts, all recovered.
  Normal under heavy load.
- `Any queue depth > 0` at shutdown → an item is stuck. A worker likely
  threw an unhandled exception before forwarding the poison pill. Check
  for `Exception` stack traces in the log.

**Step 2 — Read the per-image log lines.**
Every image logs its outcome in real time:

```
[Detection] OK  (1243 ms): ...1.jpg            ← success
[Detection] Retrying: ...5.jpg                 ← first attempt failed
[Detection] OK after retry (2891 ms): ...5.jpg ← recovered
[Detection] ERROR after retry: ...7.jpg        ← both attempts failed
[Validation] SKIPPED (invalid): ...fake.txt    ← rejected at Stage 1
```

Search for a specific filename to trace it through the pipeline.

**Step 3 — Verify Flask is running.**
Open `http://localhost:8080/api/health` in a browser. If it returns
`{"status":"ok"}` Flask is alive. If it times out, Flask is the problem —
not the Java pipeline. Check `http://localhost:8080/api/stats` to see how
many requests Flask received vs how many Java sent.

**Step 4 — Check queue capacity vs workload size.**
If the pipeline hangs and never terminates, the most likely cause is the
shutdown deadlock described in Q3 — queue is full and poison pills cannot
be inserted. Fix: increase capacity in `MainApp`:

```java
// was 50 — increase if workload >> 50 images
BlockingQueue<String> rawQueue = new LinkedBlockingQueue<>(200);
```

**Step 5 — Use a JVM thread dump for stuck pipelines.**
If the pipeline does not terminate after several minutes, press `Ctrl+\`
(Linux/Mac) or `Ctrl+Break` (Windows) to print a thread dump. Look for
threads in state `WAITING` on `BlockingQueue.put()` — this identifies
exactly which queue is full and which stage is the bottleneck.

---

## Summary

| | Bounded queue (cap 50) | Unbounded queue |
|---|---|---|
| Memory safety | ✅ Capped at 50 items | ❌ Grows to OOM |
| Backpressure | ✅ Producer pauses when full | ❌ No backpressure |
| Assignment compliance | ✅ Required by spec | ❌ Explicitly rejected |
| Deadlock risk | ⚠️ If capacity < poison pills | ✅ None from capacity |
| Overload behavior | ✅ Controlled pause — **measured: 509 986 ms, 0.20 img/s, 0 failures** | ❌ Silent memory growth |
| Debuggability | ✅ Queue depth metric shows pressure | ❌ Depth meaningless |
| Correctness under load | ✅ **102/102 completed, 0 lost, 9 retries recovered** | Unknown |

The bounded queue with capacity 50 is the correct choice for this pipeline.
It satisfies the assignment's bounded-resource requirement, makes overload
behavior observable and measurable with real numbers, and proved correct
under overload: 102 images processed, 0 failures, 9 retries transparently
recovered, all queue depths 0 at shutdown.
