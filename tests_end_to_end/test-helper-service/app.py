"""
Flask Test Helper Service for TypeScript E2E Tests

This service provides HTTP endpoints that wrap the Python Opik SDK,
allowing TypeScript tests to leverage Python SDK functionality.
"""

import os
import re
from flask import Flask, jsonify
from flask_cors import CORS

import requests  # type: ignore[import-untyped]

from routes.projects import projects_bp
from routes.datasets import datasets_bp
from routes.traces import traces_bp
from routes.threads import threads_bp
from routes.feedback_scores import feedback_scores_bp
from routes.experiments import experiments_bp
from routes.prompts import prompts_bp


def authenticate_if_needed():
    """
    Authenticate to cloud environment if needed.
    Sets OPIK_API_KEY and OPIK_URL_OVERRIDE environment variables.
    """
    base_url = os.environ.get("OPIK_BASE_URL", "http://localhost:5173")

    # Skip authentication for local environment
    if base_url.startswith("http://localhost"):
        print("🏠 Local environment detected, skipping authentication")
        return

    # Check if API key already exists
    if os.environ.get("OPIK_API_KEY"):
        print("✅ API key already set, skipping authentication")
        # Still ensure OPIK_URL_OVERRIDE is set even if API key exists
        if not os.environ.get("OPIK_URL_OVERRIDE"):
            os.environ["OPIK_URL_OVERRIDE"] = f"{base_url}/api"
            print(f"📍 Setting API URL: {os.environ.get('OPIK_URL_OVERRIDE')}")
        return

    # Get credentials from environment
    email = os.environ.get("OPIK_TEST_USER_EMAIL")
    password = os.environ.get("OPIK_TEST_USER_PASSWORD")

    if not email or not password:
        print("⚠️  Warning: No credentials provided for cloud environment")
        return

    try:
        # Remove /opik suffix from base URL for auth endpoint
        # The base URL includes '/opik' for app routing (e.g., 'https://staging.dev.comet.com/opik')
        # but the auth endpoint is at the root level (e.g., 'https://staging.dev.comet.com/api/auth/login')
        auth_base_url = re.sub(r"/opik$", "", base_url)
        auth_url = f"{auth_base_url}/api/auth/login"

        print(f"🔐 Authenticating to {auth_url}...")

        response = requests.post(
            auth_url,
            json={"email": email, "plainTextPassword": password},
            headers={"Content-Type": "application/json"},
            timeout=30,
        )

        if response.status_code != 200:
            print(f"❌ Authentication failed: {response.status_code} - {response.text}")
            return

        data = response.json()

        if "apiKeys" not in data or not data["apiKeys"]:
            print(f"❌ No API keys in response: {data}")
            return

        # Set API key in environment for SDK calls
        os.environ["OPIK_API_KEY"] = data["apiKeys"][0]

        # Set URL override if not already set
        if not os.environ.get("OPIK_URL_OVERRIDE"):
            os.environ["OPIK_URL_OVERRIDE"] = f"{base_url}/api"

        print("✅ Authentication successful")
        print(f"📍 API URL: {os.environ.get('OPIK_URL_OVERRIDE')}")
        print(f"📂 Workspace: {os.environ.get('OPIK_WORKSPACE', 'default')}")

    except Exception as e:
        print(f"❌ Authentication error: {e}")


app = Flask(__name__)
CORS(app)

app.register_blueprint(projects_bp, url_prefix="/api/projects")
app.register_blueprint(datasets_bp, url_prefix="/api/datasets")
app.register_blueprint(traces_bp, url_prefix="/api/traces")
app.register_blueprint(threads_bp, url_prefix="/api/threads")
app.register_blueprint(feedback_scores_bp, url_prefix="/api/feedback-scores")
app.register_blueprint(experiments_bp, url_prefix="/api/experiments")
app.register_blueprint(prompts_bp, url_prefix="/api/prompts")


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
    # Authenticate before starting server
    authenticate_if_needed()

    port = int(os.environ.get("TEST_HELPER_PORT", 5555))
    print(f"🚀 Starting Opik Test Helper Service on port {port}")
    print(f"📍 Health check available at: http://localhost:{port}/health")
    app.run(host="0.0.0.0", port=port, debug=False)
