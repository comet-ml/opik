from flask import Blueprint, request, jsonify
import os
from opik import Opik
from opik.rest_api.core.api_error import ApiError
import time
import logging

datasets_bp = Blueprint("datasets", __name__)
logger = logging.getLogger(__name__)


def get_opik_client():
    """Get configured Opik SDK client"""
    return Opik(
        api_key=os.getenv("OPIK_API_KEY", None),
        workspace=os.getenv("OPIK_WORKSPACE", None),
        host=os.getenv("OPIK_URL_OVERRIDE", None),
    )


@datasets_bp.route("/create", methods=["POST"])
def create_dataset():
    data = request.json
    dataset_name = data.get("name")

    client = get_opik_client()
    dataset = client.create_dataset(name=dataset_name)

    return jsonify({"id": dataset.id, "name": dataset.name})


@datasets_bp.route("/find", methods=["POST"])
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


@datasets_bp.route("/update", methods=["POST"])
def update_dataset():
    data = request.json
    dataset_name = data.get("name")
    new_name = data.get("newName")

    client = get_opik_client()
    dataset = client.get_dataset(dataset_name)
    dataset_id = dataset.id

    # Use the rest_client from the Opik client which is already properly configured
    client.rest_client.datasets.update_dataset(id=dataset_id, name=new_name)

    return jsonify({"id": dataset_id, "name": new_name})


@datasets_bp.route("/delete", methods=["DELETE"])
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


@datasets_bp.route("/wait-for-visible", methods=["POST"])
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


@datasets_bp.route("/wait-for-deleted", methods=["POST"])
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


@datasets_bp.route("/insert-items", methods=["POST"])
def insert_dataset_items():
    data = request.json
    dataset_name = data.get("dataset_name")
    items = data.get("items")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        dataset.insert(items)
        return jsonify({"success": True})
    except ApiError as e:
        logger.error(f"Error inserting dataset items: {type(e).__name__}")
        return jsonify({"error": "Failed to insert items"}), e.status_code
    except Exception as e:
        logger.error(f"Error inserting dataset items: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/get-items", methods=["POST"])
def get_dataset_items():
    data = request.json
    dataset_name = data.get("dataset_name")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        items = dataset.get_items()
        return jsonify({"items": items})
    except ApiError as e:
        logger.error(f"Error getting dataset items: {type(e).__name__}")
        return jsonify({"error": "Failed to get items"}), e.status_code
    except Exception as e:
        logger.error(f"Error getting dataset items: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/update-items", methods=["POST"])
def update_dataset_items():
    data = request.json
    dataset_name = data.get("dataset_name")
    items = data.get("items")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        dataset.update(items)
        return jsonify({"success": True})
    except ApiError as e:
        logger.error(f"Error updating dataset items: {type(e).__name__}")
        return jsonify({"error": "Failed to update items"}), e.status_code
    except Exception as e:
        logger.error(f"Error updating dataset items: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/delete-item", methods=["DELETE"])
def delete_dataset_item():
    data = request.json
    dataset_name = data.get("dataset_name")
    item_id = data.get("item_id")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        dataset.delete([item_id])
        return jsonify({"success": True})
    except ApiError as e:
        logger.error(f"Error deleting dataset item: {type(e).__name__}")
        return jsonify({"error": "Failed to delete item"}), e.status_code
    except Exception as e:
        logger.error(f"Error deleting dataset item: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/clear", methods=["POST"])
def clear_dataset():
    data = request.json
    dataset_name = data.get("dataset_name")

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        items = dataset.get_items()
        if items:
            item_ids = [item["id"] for item in items]
            dataset.delete(item_ids)
        return jsonify({"success": True})
    except ApiError as e:
        logger.error(f"Error clearing dataset: {type(e).__name__}")
        return jsonify({"error": "Failed to clear dataset"}), e.status_code
    except Exception as e:
        logger.error(f"Error clearing dataset: {type(e).__name__}")
        return jsonify({"error": "An internal error occurred"}), 500


@datasets_bp.route("/wait-for-items-count", methods=["POST"])
def wait_for_items_count():
    data = request.json
    dataset_name = data.get("dataset_name")
    expected_count = data.get("expected_count")
    timeout = data.get("timeout", 10)

    client = get_opik_client()
    start_time = time.time()

    while time.time() - start_time < timeout:
        try:
            dataset = client.get_dataset(dataset_name)
            items = dataset.get_items()
            if len(items) == expected_count:
                return jsonify({"success": True, "count": len(items)})
        except ApiError as e:
            if e.status_code == 404:
                pass
            else:
                logger.error(f"Error waiting for items count: {type(e).__name__}")
        except Exception as e:
            logger.error(f"Error waiting for items count: {type(e).__name__}")
        time.sleep(0.5)

    return (
        jsonify(
            {"error": f"Items count did not reach {expected_count} within timeout"}
        ),
        400,
    )
