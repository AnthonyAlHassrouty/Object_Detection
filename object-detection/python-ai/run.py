"""
Bird Detection AI Service — Entry Point
Run: python run.py
this file is the starting point of the AI service
"""
import os, sys  # os is used to interact with the operating system, sys is used to control python runtime

sys.path.insert(0, os.path.dirname(__file__)) # this line makes sure to find the python folder where run.py is located

from api.flask_app import app, initialize_detector # app to determine the locall web server, initialize_detector to load the YOLO model

if __name__ == "__main__":
    initialize_detector()  # to initialize the AI model befroe the server start
    port = int(os.environ.get("BIRD_SERVICE_PORT", 8080))
    app.run(host="0.0.0.0", port=port, debug=False, threaded=True)