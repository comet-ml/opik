"""
Flask Test Helper Service for TypeScript E2E Tests

This service provides HTTP endpoints that wrap the Python Opik SDK,
allowing TypeScript tests to leverage Python SDK functionality.
"""

import os
from flask import Flask, jsonify
from flask_cors import CORS

from routes.projects import projects_bp

app = Flask(__name__)
CORS(app)

app.register_blueprint(projects_bp, url_prefix="/api/projects")


@app.route("/health", methods=["GET"])
def health_check():
    return jsonify(
        {"status": "healthy", "service": "opik-test-helper", "version": "1.0.0"}
    ), 200


@app.errorhandler(404)
def not_found(error):
    return jsonify(
        {"success": False, "error": "Endpoint not found", "type": "NotFoundError"}
    ), 404


@app.errorhandler(500)
def internal_error(error):
    return jsonify(
        {"success": False, "error": str(error), "type": "InternalServerError"}
    ), 500


if __name__ == "__main__":
    port = int(os.environ.get("TEST_HELPER_PORT", 5555))
    print(f"🚀 Starting Opik Test Helper Service on port {port}")
    print(f"📍 Health check available at: http://localhost:{port}/health")
    app.run(host="0.0.0.0", port=port, debug=False)
