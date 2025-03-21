import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

from opik_guardrails import create_app

if __name__ == "__main__":
    app = create_app()
    app.config["DEBUG"] = True
    app.run(host="0.0.0.0", port=5000, debug=True)
