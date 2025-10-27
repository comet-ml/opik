from flask import Blueprint, request, jsonify
import opik
from opik.rest_api.core.api_error import ApiError
import time
import logging

datasets_bp = Blueprint("datasets", __name__)
logger = logging.getLogger(__name__)


def get_opik_client():
    return opik.Opik()


@datasets_bp.route("/api/datasets/create", methods=["POST"])
def create_dataset():
    data = request.json
    dataset_name = data.get("name")

    client = get_opik_client()
    dataset = client.create_dataset(name=dataset_name)

    return jsonify({"id": dataset.id, "name": dataset.name})


@datasets_bp.route("/api/datasets/find", methods=["POST"])
def find_dataset():
    data = request.json
    dataset_name = data.get("name")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        return jsonify({"id": dataset.id, "name": dataset.name})
    except ApiError as e:
        if e.status_code == 404:
            return jsonify(None), 404
        logger.error(f"Error finding dataset: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500
    except Exception as e:
        logger.error(f"Error finding dataset: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/api/datasets/update", methods=["POST"])
def update_dataset():
    data = request.json
    dataset_name = data.get("name")
    new_name = data.get("newName")

    client = get_opik_client()
    dataset = client.get_dataset(dataset_name)
    dataset_id = dataset.id

    from opik.rest_api.client import OpikApi

    api_client = OpikApi()
    api_client.datasets.update_dataset(id=dataset_id, name=new_name)

    return jsonify({"id": dataset_id, "name": new_name})


@datasets_bp.route("/api/datasets/delete", methods=["DELETE"])
def delete_dataset():
    data = request.json
    dataset_name = data.get("name")

    client = get_opik_client()
    try:
        client.delete_dataset(dataset_name)
        return jsonify({"success": True})
    except ApiError as e:
        if e.status_code == 404:
            return jsonify({"success": True})
        logger.error(f"Error deleting dataset: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500
    except Exception as e:
        logger.error(f"Error deleting dataset: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/api/datasets/wait-for-visible", methods=["POST"])
def wait_for_dataset_visible():
    data = request.json
    dataset_name = data.get("name")
    timeout = data.get("timeout", 10)

    client = get_opik_client()
    start_time = time.time()

    while time.time() - start_time < timeout:
        try:
            dataset = client.get_dataset(dataset_name)
            if dataset:
                return jsonify({"id": dataset.id, "name": dataset.name})
        except ApiError as e:
            if e.status_code == 404:
                pass
            else:
                logger.error(
                    f"Error waiting for dataset visibility: {type(e).__name__}"
                )
        except Exception as e:
            logger.error(f"Error waiting for dataset visibility: {type(e).__name__}")
        time.sleep(0.5)

    return jsonify({"error": "Dataset not visible within timeout"}), 404


@datasets_bp.route("/api/datasets/wait-for-deleted", methods=["POST"])
def wait_for_dataset_deleted():
    data = request.json
    dataset_name = data.get("name")
    timeout = data.get("timeout", 10)

    client = get_opik_client()
    start_time = time.time()

    while time.time() - start_time < timeout:
        try:
            client.get_dataset(dataset_name)
            time.sleep(0.5)
        except ApiError as e:
            if e.status_code == 404:
                return jsonify({"success": True})
            logger.error(f"Error waiting for dataset deletion: {type(e).__name__}")
            time.sleep(0.5)
        except Exception as e:
            logger.error(f"Error waiting for dataset deletion: {type(e).__name__}")
            time.sleep(0.5)

    return jsonify({"error": "Dataset still exists after timeout"}), 400
