"""
Project-related endpoints for test helper service
"""

from flask import Blueprint, request, jsonify
import os
import time
from opik.rest_api.client import OpikApi

projects_bp = Blueprint("projects", __name__)


def get_opik_api_client():
    return OpikApi(
        base_url=os.getenv("OPIK_URL_OVERRIDE", None),
        workspace_name=os.getenv("OPIK_WORKSPACE", None),
        api_key=os.getenv("OPIK_API_KEY", None),
    )


def create_project_via_api(name: str):
    client = get_opik_api_client()
    client.projects.create_project(name=name)


def find_project_by_name_sdk(name: str):
    client = get_opik_api_client()
    proj_page = client.projects.find_projects(name=name, page=1, size=1)
    return proj_page.dict()["content"]


def delete_project_by_name_sdk(name: str):
    client = get_opik_api_client()
    project = find_project_by_name_sdk(name=name)
    client.projects.delete_project_by_id(project[0]["id"])


def update_project_by_name_sdk(name: str, new_name: str):
    client = get_opik_api_client()
    wait_for_project_to_be_visible(name, timeout=10)
    projects_match = find_project_by_name_sdk(name)
    project_id = projects_match[0]["id"]

    client.projects.update_project(id=project_id, name=new_name)

    return project_id


def wait_for_project_to_be_visible(project_name, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        if find_project_by_name_sdk(project_name):
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"could not get created project {project_name} via API within {timeout} seconds"
    )


def wait_for_project_to_not_be_visible(project_name, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        if not find_project_by_name_sdk(project_name):
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"{project_name} has not been deleted via API within {timeout} seconds"
    )


def setup_env_from_config(config):
    if config.get("workspace"):
        os.environ["OPIK_WORKSPACE"] = config["workspace"]
    if config.get("host"):
        os.environ["OPIK_URL_OVERRIDE"] = config["host"]
    if config.get("api_key"):
        os.environ["OPIK_API_KEY"] = config["api_key"]


@projects_bp.route("/create", methods=["POST"])
def create_project():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")

        if not name:
            return jsonify(
                {
                    "success": False,
                    "error": "Project name is required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        create_project_via_api(name=name)

        return jsonify({"success": True, "project": {"name": name}}), 200

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500


@projects_bp.route("/find", methods=["POST"])
def find_project():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")

        if not name:
            return jsonify(
                {
                    "success": False,
                    "error": "Project name is required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        projects = find_project_by_name_sdk(name=name)

        return jsonify({"success": True, "projects": projects}), 200

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500


@projects_bp.route("/delete", methods=["DELETE"])
def delete_project():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")

        if not name:
            return jsonify(
                {
                    "success": False,
                    "error": "Project name is required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        delete_project_by_name_sdk(name=name)

        return jsonify(
            {"success": True, "message": f"Project {name} deleted successfully"}
        ), 200

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500


@projects_bp.route("/update", methods=["POST"])
def update_project():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")
        new_name = data.get("new_name")

        if not name or not new_name:
            return jsonify(
                {
                    "success": False,
                    "error": "Both name and new_name are required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        project_id = update_project_by_name_sdk(name=name, new_name=new_name)

        return jsonify(
            {"success": True, "project": {"id": project_id, "name": new_name}}
        ), 200

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500


@projects_bp.route("/wait-for-visible", methods=["POST"])
def wait_for_project_visible():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")
        timeout = data.get("timeout", 10)

        if not name:
            return jsonify(
                {
                    "success": False,
                    "error": "Project name is required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        wait_for_project_to_be_visible(project_name=name, timeout=timeout)

        return jsonify({"success": True, "message": f"Project {name} is visible"}), 200

    except TimeoutError as e:
        return jsonify({"success": False, "error": str(e), "type": "TimeoutError"}), 408

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500


@projects_bp.route("/wait-for-deleted", methods=["POST"])
def wait_for_project_deleted():
    try:
        data = request.get_json()
        config = data.get("config", {})
        name = data.get("name")
        timeout = data.get("timeout", 10)

        if not name:
            return jsonify(
                {
                    "success": False,
                    "error": "Project name is required",
                    "type": "ValueError",
                }
            ), 400

        setup_env_from_config(config)
        wait_for_project_to_not_be_visible(project_name=name, timeout=timeout)

        return jsonify(
            {"success": True, "message": f"Project {name} has been deleted"}
        ), 200

    except TimeoutError as e:
        return jsonify({"success": False, "error": str(e), "type": "TimeoutError"}), 408

    except Exception as e:
        return jsonify(
            {"success": False, "error": str(e), "type": type(e).__name__}
        ), 500
