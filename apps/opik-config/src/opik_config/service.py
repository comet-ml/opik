"""Flask service with SQLite-backed config store."""

import os
from flask import Flask, jsonify, request

from .sqlite_store import SQLiteConfigStore

app = Flask(__name__)

# Use environment variable for DB path, default to in-memory for dev
DB_PATH = os.environ.get("OPIK_CONFIG_DB", ":memory:")
store = SQLiteConfigStore(DB_PATH)


@app.after_request
def add_cors_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
    return response


@app.route("/health")
def health():
    return jsonify({"status": "healthy"})


# =============================================================================
# Core Resolution API
# =============================================================================

@app.route("/v1/config/resolve", methods=["POST"])
def resolve_config():
    """
    Resolve config values for a batch of keys.

    Input:
    - project_id: string
    - env: string (e.g., "prod", "staging")
    - keys: string[] (namespaced keys)
    - mask_id?: string | null
    - unit_id?: string | null

    Output:
    - resolved_values: { [key: string]: any }
    - resolved_value_ids: { [key: string]: int }
    - missing_keys: string[]
    - assigned_variant: string | null
    """
    data = request.get_json()
    if not data or "keys" not in data:
        return jsonify({"error": "Missing 'keys'"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    keys = data["keys"]
    mask_id = data.get("mask_id")
    unit_id = data.get("unit_id")

    result = store.resolve(
        project_id=project_id,
        env=env,
        keys=keys,
        mask_id=mask_id,
        unit_id=unit_id,
    )
    return jsonify(result.to_dict())


# =============================================================================
# Key Registration API
# =============================================================================

@app.route("/v1/config/keys/register", methods=["POST"])
def register_keys():
    """
    Register config key metadata (best-effort).
    Also publishes default values if not already published.

    Input:
    - project_id: string
    - env: string (default "prod")
    - keys: [{ key: string, type?: string, default_value?: any, source?: object }]

    Returns 202 Accepted.
    """
    data = request.get_json()
    if not data or "keys" not in data:
        return jsonify({"error": "Missing 'keys'"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    keys = data["keys"]

    store.register_keys(project_id, keys, env=env)
    return jsonify({"status": "accepted"}), 202


@app.route("/v1/config/keys", methods=["GET"])
def list_keys():
    """List all registered keys for a project."""
    project_id = request.args.get("project_id", "default")
    keys = store.list_keys(project_id)
    return jsonify({"keys": keys})


# =============================================================================
# Publishing API
# =============================================================================

@app.route("/v1/config/publish", methods=["POST"])
def publish_value():
    """
    Publish a value for a key in an environment.

    Input:
    - project_id: string
    - env: string
    - key: string
    - value: any (JSON-serializable)
    - created_by?: string
    """
    data = request.get_json()
    if not data or "key" not in data or "value" not in data:
        return jsonify({"error": "Missing 'key' or 'value'"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    key = data["key"]
    value = data["value"]
    created_by = data.get("created_by")

    value_id = store.publish_value(
        project_id=project_id,
        env=env,
        key=key,
        value=value,
        created_by=created_by,
    )
    return jsonify({"value_id": value_id}), 201


@app.route("/v1/config/published", methods=["GET"])
def list_published():
    """List all published values for an environment."""
    project_id = request.args.get("project_id", "default")
    env = request.args.get("env", "prod")
    values = store.list_published(project_id, env)
    return jsonify({"values": values})


# =============================================================================
# Masks/Experiments API
# =============================================================================

@app.route("/v1/config/masks", methods=["POST"])
def create_mask():
    """
    Create or update a mask/experiment.

    Input:
    - project_id: string
    - env: string
    - mask_id: string
    - name?: string (auto-generated if not provided, e.g. "happy-falcon-4821")
    - is_ab?: boolean (default false)
    - distribution?: { [variant: string]: int } (e.g., {"A": 50, "B": 50})
    - salt?: string
    """
    data = request.get_json()
    if not data or "mask_id" not in data:
        return jsonify({"error": "Missing 'mask_id'"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    mask_id = data["mask_id"]
    name = data.get("name")
    is_ab = data.get("is_ab", False)
    distribution = data.get("distribution")
    salt = data.get("salt")

    generated_name = store.create_or_update_mask(
        project_id=project_id,
        env=env,
        mask_id=mask_id,
        name=name,
        is_ab=is_ab,
        distribution=distribution,
        salt=salt,
    )
    return jsonify({"status": "created", "name": generated_name}), 201


@app.route("/v1/config/masks", methods=["GET"])
def list_masks():
    """List all masks for an environment."""
    project_id = request.args.get("project_id", "default")
    env = request.args.get("env", "prod")
    masks = store.list_masks(project_id, env)
    return jsonify({"masks": masks})


@app.route("/v1/config/masks/<mask_id>/overrides", methods=["GET"])
def get_mask_overrides(mask_id: str):
    """Get all overrides for a specific mask."""
    project_id = request.args.get("project_id", "default")
    env = request.args.get("env", "prod")
    overrides = store.list_mask_overrides(project_id, env, mask_id)
    return jsonify({"overrides": overrides})


@app.route("/v1/config/masks/override", methods=["POST"])
def set_mask_override():
    """
    Set an override value for a specific variant of a mask.

    Input:
    - project_id: string
    - env: string
    - mask_id: string
    - variant: string (e.g., "A", "B", "default")
    - key: string
    - value: any (JSON-serializable)
    - created_by?: string
    """
    data = request.get_json()
    required = ["mask_id", "variant", "key", "value"]
    if not data or any(k not in data for k in required):
        return jsonify({"error": f"Missing required fields: {required}"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    mask_id = data["mask_id"]
    variant = data["variant"]
    key = data["key"]
    value = data["value"]
    created_by = data.get("created_by")

    value_id = store.set_mask_override(
        project_id=project_id,
        env=env,
        mask_id=mask_id,
        variant=variant,
        key=key,
        value=value,
        created_by=created_by,
    )
    return jsonify({"value_id": value_id}), 201


@app.route("/v1/config/prompts/override", methods=["POST"])
def set_prompt_override():
    """
    Set an override for a prompt by its name (simplified API for optimizers).

    The service looks up which config key has this prompt name and sets the override.

    Input:
    - project_id?: string (default "default")
    - env?: string (default "prod")
    - mask_id: string (the experiment/optimization run ID)
    - prompt_name: string (e.g., "Researcher System Prompt")
    - value: string (the new prompt text)
    - variant?: string (default "default")
    - created_by?: string
    """
    data = request.get_json()
    required = ["mask_id", "prompt_name", "value"]
    if not data or any(k not in data for k in required):
        return jsonify({"error": f"Missing required fields: {required}"}), 400

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    mask_id = data["mask_id"]
    prompt_name = data["prompt_name"]
    prompt_value = data["value"]
    variant = data.get("variant", "default")
    created_by = data.get("created_by")

    key = store.find_key_by_prompt_name(project_id, env, prompt_name)
    if not key:
        return jsonify({"error": f"No config key found for prompt_name: {prompt_name}"}), 404

    value_id = store.set_mask_override(
        project_id=project_id,
        env=env,
        mask_id=mask_id,
        variant=variant,
        key=key,
        value={"prompt_name": prompt_name, "prompt": prompt_value},
        created_by=created_by,
    )
    return jsonify({"value_id": value_id, "key": key}), 201


# =============================================================================
# History/Debug API
# =============================================================================

@app.route("/v1/config/history/<key>", methods=["GET"])
def get_value_history(key: str):
    """Get value history for a key."""
    project_id = request.args.get("project_id", "default")
    limit = int(request.args.get("limit", "100"))
    history = store.get_value_history(project_id, key, limit)
    return jsonify({"history": history})


@app.route("/kv_viewer")
def kv_viewer():
    """Debug endpoint to view all table contents."""
    data = store.dump_all_tables()
    return jsonify(data)


def run_service(host: str = "127.0.0.1", port: int = 5050, debug: bool = False) -> None:
    app.run(host=host, port=port, debug=debug)
