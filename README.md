# Object Detection — Concurrent & Distributed Pipeline

A concurrent image object-detection system built as a final project for the
**Concurrent & Distributed Programming** course (Semester VIII — ULFG).

---

## What does it do?

This system takes a folder of images and detects all objects inside them
(birds, people, cars, etc.) as fast as possible using **parallelism**.

Instead of processing one image at a time, the system processes up to
**10 images simultaneously** using a multi-stage parallel pipeline, achieving
nearly **8× speedup** over sequential processing.

---

## How it works

The system is split into two parts that run at the same time:

**Java side — the concurrency engine**
Reads the image folder and pushes every image through a 3-stage parallel
pipeline: validation → AI detection → post-processing. Each stage runs
10 threads in parallel, connected by bounded queues.

**Python side — the AI service**
A Flask web server that holds a YOLOv8 object detection model. It receives
HTTP requests from the Java pipeline, runs the AI model on each image, and
returns the detected objects as JSON.

```
[Java Pipeline]  ──HTTP──▶  [Python Flask + YOLOv8]
```

---

## What's inside

```
object-detection/
├── java-concurrency/     ← Java 21 parallel pipeline (10 classes)
├── python-ai/            ← Python Flask AI service (YOLOv8)
├── evidence/             ← Metrics, benchmarks, screenshots, scorecard
├── README.md             ← Full technical documentation
└── architecture-decision-memo.md  ← Design decision analysis
```

The `object-detection/` folder contains the complete project with full
technical documentation, run instructions, architecture diagrams, benchmark
results, failure injection tests, and a concurrency scorecard.

➡️ **See [`object-detection/README.md`](object-detection/README.md) for the full documentation.**

---

## Key results

| Metric | Value |
|---|---|
| Images processed | 34 (normal) / 102 (overload test) |
| Parallel speedup | **7.84×** faster than sequential |
| Throughput | 0.70 img/s (normal) / 0.20 img/s (overload) |
| Pipeline stages | 3 (validate → detect → post-process) |
| Threads | 30 total (10 per stage) |
| Failure recovery | Automatic retry with 1s backoff |

---

## Built with

- **Java 21** — concurrency engine (`ExecutorService`, `BlockingQueue`, `CountDownLatch`)
- **Python 3** — Flask web service + YOLOv8 AI model
- **YOLOv8x** — state-of-the-art object detection model by Ultralytics
