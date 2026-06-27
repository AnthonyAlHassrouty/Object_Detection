from ultralytics import YOLO
from collections import defaultdict


class ObjectDetector:
    """
    Detects multiple objects in an image using YOLOv8.
    """

    def __init__(self, model_name="yolov8x.pt", confidence=0.4):
        self.model = YOLO(model_name)
        self.confidence = confidence

    def detect_objects(self, image_path):
        """
        Detect ALL objects in image.
        """

        results = self.model(image_path, conf=self.confidence, imgsz=1280)

        counts = defaultdict(int)
        detections = []

        for box in results[0].boxes:
            class_id = int(box.cls[0])
            conf = float(box.conf[0])

            if conf >= self.confidence:
                name = self.model.names[class_id]  # convert the id to an object name

                x1, y1, x2, y2 = box.xyxy[0].tolist() # get the position of the object in the image

                detections.append({ #save the object informations
                    "class": name,
                    "confidence": conf,
                    "box": {
                        "x1": int(x1),
                        "y1": int(y1),
                        "x2": int(x2),
                        "y2": int(y2)
                    }
                })

                counts[name] += 1

        return {
            "counts": dict(counts),
            "detections": detections
        }