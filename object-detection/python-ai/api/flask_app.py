import os
import time
import threading  # FIX: needed for the stats lock
from flask import Flask, request, jsonify
from flask_cors import CORS

from trainer.object_detector import ObjectDetector

# -----------------------------
# Flask App Setup
# -----------------------------
app = Flask(__name__)
CORS(app)

# Load model ONCE (important for performance + concurrency)
detector = ObjectDetector()

# -----------------------------
# Thread-safe stats tracker
# FIX: plain dict += is NOT atomic under threaded=True;
#      protect every read-modify-write with a Lock.
# -----------------------------
stats = {
    "requests": 0,
    "images": 0,
    "objects_detected": 0,
    "start_time": time.time()
}
stats_lock = threading.Lock()  # FIX: one lock guards the whole stats dict

# -----------------------------
# Initialization (called from run.py)
# -----------------------------
def initialize_detector():
    """
    Ensures model is loaded before server starts.
    """
    detector.model  # already loaded in constructor


# -----------------------------
# HEALTH CHECK
# -----------------------------
@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "model_ready": True,
        "uptime": round(time.time() - stats["start_time"], 2)
    })


# -----------------------------
# MAIN DETECTION ENDPOINT
# -----------------------------
@app.route("/api/detect", methods=["POST"])
def detect():

    # FIX: acquire lock before touching shared stats
    with stats_lock:
        stats["requests"] += 1

    data = request.get_json()

    if not data or "image_path" not in data:
        return jsonify({"error": "missing image_path"}), 400

    image_path = data["image_path"]

    # Read the trace ID sent by Java DetectionWorker.
    # Logging it here links this Flask log line to the Java log line
    # that carries the same trace_id — request tracing across the network.
    trace_id = data.get("trace_id", "no-trace")
    print(f"[TRACE {trace_id}] received: {os.path.basename(image_path)}", flush=True)

    if not os.path.exists(image_path):
        print(f"[TRACE {trace_id}] ERROR: image not found", flush=True)
        return jsonify({"error": "image not found"}), 404

    # Run YOLO detection (no lock needed — ObjectDetector is stateless per call)
    result = detector.detect_objects(image_path)

    total_objects = sum(result["counts"].values())
    print(f"[TRACE {trace_id}] done: {total_objects} objects detected", flush=True)

    # FIX: lock again to update the remaining counters atomically
    with stats_lock:
        stats["images"]           += 1
        stats["objects_detected"] += total_objects

    return jsonify(result)


# -----------------------------
# STATS ENDPOINT
# -----------------------------
@app.route("/api/stats", methods=["GET"])
def get_stats():
    with stats_lock:  # FIX: read under lock too
        snapshot = dict(stats)

    return jsonify({
        "total_requests":        snapshot["requests"],
        "total_images":          snapshot["images"],
        "total_objects_detected": snapshot["objects_detected"],
        "uptime": round(time.time() - snapshot["start_time"], 2)
    })
