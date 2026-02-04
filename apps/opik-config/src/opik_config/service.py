"""Flask service with SQLite-backed config store."""

import gzip
import logging
import os
from io import BytesIO
from typing import Any

from flask import Flask, jsonify, request
from werkzeug.wsgi import get_input_stream

from .sqlite_store import SQLiteConfigStore
from .prompt_bridge import PromptBridge

LOGGER = logging.getLogger(__name__)

app = Flask(__name__)


class GzipMiddleware:
    """WSGI middleware that decompresses gzip-encoded request bodies."""

    def __init__(self, app):
        self.app = app

    def __call__(self, environ, start_response):
        content_encoding = environ.get("HTTP_CONTENT_ENCODING", "").lower()
        if content_encoding == "gzip":
            try:
                input_stream = get_input_stream(environ)
                compressed_data = input_stream.read()
                decompressed_data = gzip.decompress(compressed_data)
                environ["wsgi.input"] = BytesIO(decompressed_data)
                environ["CONTENT_LENGTH"] = str(len(decompressed_data))
                # Remove the encoding header so Flask doesn't get confused
                del environ["HTTP_CONTENT_ENCODING"]
            except Exception as e:
                LOGGER.warning(f"Failed to decompress gzip request: {e}")
        return self.app(environ, start_response)


app.wsgi_app = GzipMiddleware(app.wsgi_app)

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
    - experiment_type?: string ('live', 'ab', 'optimizer')
    - is_ab?: boolean (deprecated, use experiment_type instead)
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
    experiment_type = data.get("experiment_type")
    is_ab = data.get("is_ab", False)
    distribution = data.get("distribution")
    salt = data.get("salt")

    generated_name = store.create_or_update_mask(
        project_id=project_id,
        env=env,
        mask_id=mask_id,
        name=name,
        is_ab=is_ab,
        experiment_type=experiment_type,
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


# =============================================================================
# Prompt Versioning API (with Mock Mode for demos)
# =============================================================================

# Use mock mode by default for demos (set OPIK_USE_REAL_BACKEND=1 to use real Opik)
USE_MOCK_PROMPTS = os.environ.get("OPIK_USE_REAL_BACKEND", "").lower() not in ("1", "true")


def _get_prompt_bridge() -> PromptBridge:
    """Get a PromptBridge instance (mock mode or real Opik)."""
    if USE_MOCK_PROMPTS:
        return PromptBridge(config_store=store, use_mock=True)

    try:
        from opik.api_objects import opik_client
        from opik.api_objects.prompt import client as prompt_client

        opik_client_ = opik_client.get_client_cached()
        prompt_client_ = prompt_client.PromptClient(opik_client_.rest_client)
        return PromptBridge(config_store=store, opik_prompt_client=prompt_client_)
    except Exception as e:
        LOGGER.warning(f"Could not create real Opik client, using mock: {e}")
        return PromptBridge(config_store=store, use_mock=True)


@app.route("/v1/config/prompts/commit", methods=["POST"])
def commit_prompt_to_opik():
    """
    Commit experiment variant to Opik as permanent version.

    Input:
    - project_id?: string (default "default")
    - env?: string (default "prod")
    - mask_id: string (the experiment ID)
    - prompt_name: string (e.g., "Researcher System Prompt")
    - variant?: string (default "default")
    - metadata?: object

    Output:
    - prompt_name: string
    - commit: string (8-char hash)
    - opik_prompt_id: string (UUID)
    - opik_version_id: string (UUID)
    """
    data = request.get_json()
    required = ["mask_id", "prompt_name"]
    if not data or any(k not in data for k in required):
        return jsonify({"error": f"Missing required fields: {required}"}), 400

    bridge = _get_prompt_bridge()

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")
    mask_id = data["mask_id"]
    prompt_name = data["prompt_name"]
    variant = data.get("variant", "default")
    metadata = data.get("metadata")

    try:
        result = bridge.commit_experiment_variant(
            project_id=project_id,
            env=env,
            mask_id=mask_id,
            prompt_name=prompt_name,
            variant=variant,
            metadata=metadata,
        )
        return jsonify(result), 201
    except ValueError as e:
        return jsonify({"error": str(e)}), 404
    except Exception as e:
        LOGGER.error(f"Failed to commit prompt: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/v1/config/prompts/versions/<prompt_name>", methods=["GET"])
def get_prompt_version_info(prompt_name: str):
    """
    Get current Opik version info for a prompt.

    Output:
    - prompt_name: string
    - commit: string | null
    - opik_prompt_id: string | null
    - opik_version_id: string | null
    """
    project_id = request.args.get("project_id", "default")

    mapping = store.get_prompt_mapping(project_id, prompt_name)
    if not mapping:
        return jsonify({"error": f"No mapping found for prompt '{prompt_name}'"}), 404

    return jsonify({
        "prompt_name": mapping["prompt_name"],
        "commit": mapping["latest_commit"],
        "opik_prompt_id": mapping["opik_prompt_id"],
        "opik_version_id": mapping["latest_opik_version_id"],
    })


@app.route("/v1/config/prompts/sync", methods=["POST"])
def sync_prompts_to_opik():
    """
    Sync all registered prompts to Opik (creates versions if changed).

    Input:
    - project_id?: string (default "default")
    - env?: string (default "prod")

    Output:
    - synced: [{prompt_name, commit, action: "created"|"unchanged"|"skipped"}]
    """
    data = request.get_json() or {}

    bridge = _get_prompt_bridge()

    project_id = data.get("project_id", "default")
    env = data.get("env", "prod")

    try:
        results = bridge.sync_all_prompts(project_id=project_id, env=env)
        return jsonify({"synced": results})
    except Exception as e:
        LOGGER.error(f"Failed to sync prompts: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/v1/config/prompts/mappings", methods=["GET"])
def list_prompt_mappings():
    """List all prompt mappings for a project."""
    project_id = request.args.get("project_id", "default")
    mappings = store.list_prompt_mappings(project_id)
    return jsonify({"mappings": mappings})


# =============================================================================
# Mock Prompt Library API (matches Opik frontend expectations)
# =============================================================================

@app.route("/v1/private/prompts/", methods=["OPTIONS"])
def prompts_options():
    """Handle CORS preflight for prompts endpoint."""
    return "", 204


@app.route("/v1/private/prompts/<prompt_id>", methods=["OPTIONS"])
@app.route("/v1/private/prompts/<prompt_id>/versions", methods=["OPTIONS"])
def prompts_id_options(prompt_id: str = ""):
    """Handle CORS preflight for prompts/<id> endpoints."""
    return "", 204


@app.route("/v1/private/prompts/versions", methods=["OPTIONS"])
@app.route("/v1/private/prompts/versions/retrieve", methods=["OPTIONS"])
def prompts_versions_options():
    """Handle CORS preflight for prompts/versions endpoints."""
    return "", 204


@app.route("/v1/private/prompts/", methods=["GET"])
def list_prompts():
    """
    List all prompts (mock Opik Prompt Library).
    Matches Opik frontend's usePromptsList expectations.

    Query params:
    - page: int (default 1)
    - size: int (default 10)
    - name: string (search filter)

    Output (matches Opik format):
    - content: Prompt[]
    - total: number
    - sortable_by: string[]
    """
    page = int(request.args.get("page", 1))
    size = int(request.args.get("size", 10))
    name_filter = request.args.get("name", "")

    all_prompts = store.mock_list_prompts_with_versions()

    # Filter by name if provided
    if name_filter:
        all_prompts = [p for p in all_prompts if name_filter.lower() in p["name"].lower()]

    total = len(all_prompts)

    # Paginate
    start = (page - 1) * size
    end = start + size
    prompts = all_prompts[start:end]

    return jsonify({
        "content": prompts,
        "total": total,
        "page": page,
        "size": size,
        "sortable_by": ["name", "created_at", "last_updated_at"],
    })


@app.route("/v1/private/prompts/", methods=["POST"])
def create_prompt():
    """
    Create a new prompt (mock Opik Prompt Library).

    Input:
    - name: string
    - template: string
    - metadata?: object
    - description?: string
    - change_description?: string
    - template_structure?: "text" | "chat"
    - type?: "mustache" | "jinja2"
    - tags?: string[]

    Output: PromptWithLatestVersion with Location header
    """
    try:
        data = request.get_json()
        LOGGER.info(f"Create prompt request: {data}")

        if not data or "name" not in data or "template" not in data:
            return jsonify({"error": "Missing 'name' or 'template'"}), 400

        # Include type in metadata if provided
        metadata = data.get("metadata") or {}
        if data.get("type"):
            metadata["type"] = data["type"]

        result = store.mock_create_prompt(
            name=data["name"],
            template=data["template"],
            metadata=metadata if metadata else None,
            description=data.get("description"),
            change_description=data.get("change_description"),
            template_structure=data.get("template_structure", "text"),
            created_by=data.get("created_by"),
        )

        response = jsonify(result)
        response.status_code = 201
        response.headers["Location"] = f"/v1/private/prompts/{result['id']}"
        return response
    except Exception as e:
        LOGGER.exception(f"Error creating prompt: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/v1/private/prompts/<prompt_id>", methods=["GET"])
def get_prompt_by_id(prompt_id: str):
    """
    Get a prompt by ID (mock Opik Prompt Library).

    Output: PromptWithLatestVersion
    """
    result = store.mock_get_prompt_by_id(prompt_id)
    if not result:
        return jsonify({"error": f"Prompt '{prompt_id}' not found"}), 404
    return jsonify(result)


@app.route("/v1/private/prompts/versions/retrieve", methods=["POST"])
def retrieve_prompt_version():
    """
    Retrieve prompt version by name (and optionally commit).
    This is what the Opik SDK calls when creating/syncing prompts.

    Input:
    - name: string
    - commit?: string (if null, returns latest)

    Output: PromptVersionDetail
    """
    data = request.get_json()
    if not data or "name" not in data:
        return jsonify({"error": "Missing 'name'"}), 400

    name = data["name"]
    commit = data.get("commit")

    result = store.mock_get_prompt(name=name, commit=commit)
    if not result:
        return jsonify({"error": f"Prompt '{name}' not found"}), 404

    # Return PromptVersionDetail shape that SDK expects
    return jsonify({
        "id": result.get("id"),
        "prompt_id": result.get("prompt_id"),
        "commit": result.get("commit"),
        "template": result.get("template"),
        "metadata": result.get("metadata"),
        "change_description": result.get("change_description"),
        "created_at": result.get("created_at"),
        "created_by": result.get("created_by"),
        "type": "mustache",
        "template_structure": result.get("template_structure", "text"),
    })


@app.route("/v1/private/prompts/versions/<version_id>", methods=["GET"])
def get_prompt_version_by_id(version_id: str):
    """
    Get a single prompt version by version ID.

    Output: PromptVersion
    """
    result = store.mock_get_version_by_id(version_id)
    if not result:
        return jsonify({"error": f"Version '{version_id}' not found"}), 404
    return jsonify(result)


@app.route("/v1/private/prompts/versions", methods=["POST"])
def create_prompt_version_by_name():
    """
    Create a new prompt version by name (SDK endpoint).

    This is what the Opik SDK calls - it uses name instead of prompt_id.

    Input:
    - name: string (prompt name)
    - version: object (contains template, metadata, type)
    - template_structure?: "text" | "chat"

    Output: PromptVersionDetail
    """
    try:
        data = request.get_json()
        LOGGER.info(f"Create prompt version by name request: {data}")

        if not data or "name" not in data or "version" not in data:
            return jsonify({"error": "Missing 'name' or 'version'"}), 400

        name = data["name"]
        version = data["version"]
        template_structure = data.get("template_structure", "text")

        # Get the template from version object
        template = version.get("template")
        if not template:
            return jsonify({"error": "Missing 'template' in version"}), 400

        metadata = version.get("metadata")
        prompt_type = version.get("type", "mustache")

        # First check if prompt exists
        existing = store.mock_get_prompt(name=name)
        if existing:
            # For existing prompts, template_structure is immutable - use existing value
            existing_structure = existing.get("template_structure", "text")
            # Create new version of existing prompt
            result = store.mock_create_prompt_version(
                prompt_id=existing["prompt_id"],
                template=template,
                metadata=metadata,
            )
            if not result:
                return jsonify({"error": f"Failed to create version for '{name}'"}), 500
            result["template_structure"] = existing_structure
        else:
            # Create new prompt with first version
            full_result = store.mock_create_prompt(
                name=name,
                template=template,
                metadata=metadata,
                template_structure=template_structure,
            )
            latest = full_result.get("latest_version", {})
            result = {
                "id": latest.get("id"),
                "prompt_id": full_result.get("id"),
                "commit": latest.get("commit"),
                "template": latest.get("template"),
                "metadata": latest.get("metadata"),
                "type": prompt_type,
                "template_structure": template_structure,
                "created_at": latest.get("created_at"),
                "created_by": latest.get("created_by"),
            }

        # Return PromptVersionDetail shape
        response_data = {
            "id": result.get("id"),
            "prompt_id": result.get("prompt_id"),
            "commit": result.get("commit"),
            "template": result.get("template"),
            "metadata": result.get("metadata"),
            "type": result.get("type", "mustache"),
            "template_structure": result.get("template_structure", "text"),
            "created_at": result.get("created_at"),
            "created_by": result.get("created_by"),
        }

        return jsonify(response_data), 200

    except Exception as e:
        LOGGER.exception(f"Error creating prompt version: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/v1/private/prompts/<prompt_id>/versions", methods=["GET"])
def list_prompt_versions_by_id(prompt_id: str):
    """
    List all versions of a prompt by ID (mock Opik Prompt Library).

    Query params:
    - page: int (default 1)
    - size: int (default 10)

    Output:
    - content: PromptVersion[]
    - total: number
    - page: number
    - size: number
    - sortable_by: string[]
    """
    page = int(request.args.get("page", 1))
    size = int(request.args.get("size", 10))

    all_versions = store.mock_list_prompt_versions_by_id(prompt_id)
    total = len(all_versions)

    # Paginate
    start = (page - 1) * size
    end = start + size
    versions = all_versions[start:end]

    return jsonify({
        "content": versions,
        "total": total,
        "page": page,
        "size": size,
        "sortable_by": ["created_at", "commit"],
    })


@app.route("/v1/private/prompts/<prompt_id>/versions", methods=["POST"])
def create_prompt_version(prompt_id: str):
    """
    Create a new version of a prompt (mock Opik Prompt Library).

    Input:
    - template: string
    - metadata?: object
    - change_description?: string

    Output: PromptVersion
    """
    data = request.get_json()
    if not data or "template" not in data:
        return jsonify({"error": "Missing 'template'"}), 400

    result = store.mock_create_prompt_version(
        prompt_id=prompt_id,
        template=data["template"],
        metadata=data.get("metadata"),
        change_description=data.get("change_description"),
        created_by=data.get("created_by"),
    )
    if not result:
        return jsonify({"error": f"Prompt '{prompt_id}' not found"}), 404
    return jsonify(result), 201


# Legacy simple endpoints (for config service internal use)
@app.route("/v1/prompts", methods=["GET"])
def list_prompts_simple():
    """Simple list for internal use."""
    prompts = store.mock_list_prompts()
    return jsonify({"prompts": prompts})


@app.route("/v1/prompts/<name>", methods=["GET"])
def get_prompt_by_name(name: str):
    """Get prompt by name for internal use."""
    commit = request.args.get("commit")
    result = store.mock_get_prompt(name=name, commit=commit)
    if not result:
        return jsonify({"error": f"Prompt '{name}' not found"}), 404
    return jsonify(result)


# =============================================================================
# Evaluation Suites API
# =============================================================================

@app.route("/v1/eval-suites", methods=["GET"])
def list_eval_suites():
    """
    List all evaluation suites for a project.

    Query params:
    - project_id: string (default "default")

    Response:
    {
        "suites": [
            {"id": "...", "name": "Customer Support QA", "item_count": 12, ...}
        ]
    }
    """
    project_id = request.args.get("project_id", "default")
    suites = store.list_eval_suites(project_id)
    return jsonify({"suites": suites})


@app.route("/v1/eval-suites", methods=["POST"])
def create_eval_suite():
    """
    Create a new evaluation suite.

    Input:
    {
        "name": "My Test Suite",
        "project_id": "default"
    }

    Response:
    {"id": "...", "name": "My Test Suite", "item_count": 0}
    """
    data = request.get_json()
    if not data or "name" not in data:
        return jsonify({"error": "Missing 'name'"}), 400

    name = data["name"]
    project_id = data.get("project_id", "default")
    created_by = data.get("created_by")

    existing = store.get_eval_suite_by_name(project_id, name)
    if existing:
        return jsonify(existing)

    result = store.create_eval_suite(
        name=name,
        project_id=project_id,
        created_by=created_by,
    )
    return jsonify(result), 201


@app.route("/v1/eval-suites/<suite_id>", methods=["GET"])
def get_eval_suite(suite_id: str):
    """Get an evaluation suite by ID."""
    result = store.get_eval_suite(suite_id)
    if not result:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404
    return jsonify(result)


@app.route("/v1/eval-suites/<suite_id>", methods=["DELETE"])
def delete_eval_suite(suite_id: str):
    """Delete an evaluation suite and all its items."""
    deleted = store.delete_eval_suite(suite_id)
    if not deleted:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404
    return jsonify({"success": True})


@app.route("/v1/eval-suites/<suite_id>/items", methods=["GET"])
def list_eval_suite_items(suite_id: str):
    """
    Get all items in an evaluation suite (for running evaluations).

    Response:
    {
        "items": [
            {
                "id": "...",
                "input_data": {"topic": "AI in Education"},
                "assertions": ["Should be concise"],
                "trace_id": "..."
            }
        ]
    }
    """
    suite = store.get_eval_suite(suite_id)
    if not suite:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404

    items = store.list_eval_suite_items(suite_id)
    return jsonify({"items": items})


@app.route("/v1/eval-suites/<suite_id>/items", methods=["POST"])
def add_eval_suite_item(suite_id: str):
    """
    Add an item to an evaluation suite.

    Input:
    {
        "input_data": {"topic": "AI in Education"},
        "assertions": ["Should be concise", "Should include statistics"],
        "trace_id": "019c2658-..."  // optional
    }

    Response:
    {"id": "...", "suite_id": "...", "success": true}
    """
    data = request.get_json()
    if not data or "input_data" not in data or "assertions" not in data:
        return jsonify({"error": "Missing 'input_data' or 'assertions'"}), 400

    input_data = data["input_data"]
    assertions = data["assertions"]
    trace_id = data.get("trace_id")

    result = store.add_eval_suite_item(
        suite_id=suite_id,
        input_data=input_data,
        assertions=assertions,
        trace_id=trace_id,
    )

    if not result:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404

    return jsonify({**result, "success": True}), 201


@app.route("/v1/eval-suites/<suite_id>/items/<item_id>", methods=["DELETE"])
def delete_eval_suite_item(suite_id: str, item_id: str):
    """Delete an item from an evaluation suite."""
    deleted = store.delete_eval_suite_item(suite_id, item_id)
    if not deleted:
        return jsonify({"error": f"Item '{item_id}' not found in suite '{suite_id}'"}), 404
    return jsonify({"success": True})


# =============================================================================
# Evaluation Runs API
# =============================================================================

@app.route("/v1/eval-suites/<suite_id>/runs", methods=["GET"])
def list_eval_runs(suite_id: str):
    """
    List all runs for an evaluation suite.

    Query params:
    - limit: int (default 20)

    Response:
    {
        "runs": [
            {
                "id": "...",
                "status": "completed",
                "pass_rate": 0.83,
                "total_items": 12,
                "passed_items": 10,
                ...
            }
        ]
    }
    """
    suite = store.get_eval_suite(suite_id)
    if not suite:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404

    limit = int(request.args.get("limit", "20"))
    runs = store.list_eval_runs(suite_id, limit=limit)
    return jsonify({"runs": runs})


@app.route("/v1/eval-suites/<suite_id>/runs", methods=["POST"])
def create_eval_run(suite_id: str):
    """
    Create a new evaluation run.

    Input:
    {
        "experiment_id": "...",  // optional
        "created_by": "..."      // optional
    }

    Response:
    {"id": "...", "status": "pending", ...}
    """
    data = request.get_json() or {}

    experiment_id = data.get("experiment_id")
    created_by = data.get("created_by")

    result = store.create_eval_run(
        suite_id=suite_id,
        experiment_id=experiment_id,
        created_by=created_by,
    )

    if not result:
        return jsonify({"error": f"Suite '{suite_id}' not found"}), 404

    return jsonify(result), 201


@app.route("/v1/eval-runs/<run_id>", methods=["GET"])
def get_eval_run(run_id: str):
    """Get an evaluation run by ID."""
    result = store.get_eval_run(run_id)
    if not result:
        return jsonify({"error": f"Run '{run_id}' not found"}), 404
    return jsonify(result)


@app.route("/v1/eval-runs/<run_id>", methods=["PATCH"])
def update_eval_run(run_id: str):
    """
    Update an evaluation run's status.

    Input:
    {
        "status": "running" | "completed" | "failed",
        "total_items": 12,    // optional
        "passed_items": 10    // optional
    }

    Response:
    {"success": true}
    """
    data = request.get_json()
    if not data or "status" not in data:
        return jsonify({"error": "Missing 'status'"}), 400

    status = data["status"]
    if status not in ("pending", "running", "completed", "failed"):
        return jsonify({"error": f"Invalid status: {status}"}), 400

    total_items = data.get("total_items")
    passed_items = data.get("passed_items")

    updated = store.update_eval_run_status(
        run_id=run_id,
        status=status,
        total_items=total_items,
        passed_items=passed_items,
    )

    if not updated:
        return jsonify({"error": f"Run '{run_id}' not found"}), 404

    return jsonify({"success": True})


@app.route("/v1/eval-runs/<run_id>", methods=["DELETE"])
def delete_eval_run(run_id: str):
    """Delete an evaluation run and all its results."""
    deleted = store.delete_eval_run(run_id)
    if not deleted:
        return jsonify({"error": f"Run '{run_id}' not found"}), 404
    return jsonify({"success": True})


@app.route("/v1/eval-runs/<run_id>/results", methods=["GET"])
def list_eval_run_results(run_id: str):
    """
    List all results for an evaluation run.

    Response:
    {
        "results": [
            {
                "id": "...",
                "item_id": "...",
                "passed": true,
                "assertion_results": [{"name": "...", "passed": true}],
                "input_data": {...},
                ...
            }
        ]
    }
    """
    run = store.get_eval_run(run_id)
    if not run:
        return jsonify({"error": f"Run '{run_id}' not found"}), 404

    results = store.list_eval_run_results(run_id)
    return jsonify({"results": results})


@app.route("/v1/eval-runs/<run_id>/results", methods=["POST"])
def add_eval_run_result(run_id: str):
    """
    Add a result for an item in an evaluation run.

    Input:
    {
        "item_id": "...",
        "passed": true,
        "assertion_results": [{"name": "Should be concise", "passed": true}],
        "trace_id": "...",      // optional
        "duration_ms": 1234,    // optional
        "error_message": "..."  // optional
    }

    Response:
    {"id": "...", "passed": true, ...}
    """
    data = request.get_json()
    required = ["item_id", "passed", "assertion_results"]
    if not data or any(k not in data for k in required):
        return jsonify({"error": f"Missing required fields: {required}"}), 400

    result = store.add_eval_run_result(
        run_id=run_id,
        item_id=data["item_id"],
        passed=data["passed"],
        assertion_results=data["assertion_results"],
        trace_id=data.get("trace_id"),
        duration_ms=data.get("duration_ms"),
        error_message=data.get("error_message"),
    )

    if not result:
        return jsonify({"error": f"Run '{run_id}' or item not found"}), 404

    return jsonify(result), 201


def run_service(host: str = "127.0.0.1", port: int = 5050, debug: bool = False) -> None:
    app.run(host=host, port=port, debug=debug)
