"""Experiment-related endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
from opik.evaluation import evaluate
from opik.evaluation.metrics import Contains
import json
from .utils import (
    get_opik_client,
    get_opik_api_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

experiments_bp = Blueprint("experiments", __name__)


@experiments_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@experiments_bp.errorhandler(404)
def not_found(exception: HTTPException):
    return build_error_response(exception, 404)


@experiments_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


def eval_task(item: dict):
    """Simple evaluation task function"""
    return {"input": item["input"], "output": item["output"], "reference": "output"}


@experiments_bp.route("/create-experiment", methods=["POST"])
def create_experiment():
    data = request.get_json()
    validate_required_fields(data, ["experiment_name", "dataset_name"])

    experiment_name = data["experiment_name"]
    dataset_name = data["dataset_name"]
    client = get_opik_client()

    dataset = client.get_dataset(name=dataset_name)

    if not dataset:
        abort(404, f"Dataset not found: {dataset_name}")

    evaluation = evaluate(
        experiment_name=experiment_name,
        dataset=dataset,
        task=eval_task,
        scoring_metrics=[Contains()],
    )

    return success_response(
        {
            "id": evaluation.experiment_id,
            "name": experiment_name,
            "dataset_name": dataset_name,
        }
    )


@experiments_bp.route("/get-experiment", methods=["GET"])
def get_experiment():
    experiment_id = request.args.get("experiment_id")
    if not experiment_id:
        abort(400, "Missing required parameter: experiment_id")
    client = get_opik_api_client()

    try:
        experiment = client.experiments.get_experiment_by_id(id=experiment_id)
    except Exception as e:
        if "NotFoundError" in str(type(e).__name__) or "404" in str(e):
            abort(404, f"Experiment not found: {experiment_id}")
        raise

    if not experiment:
        abort(404, f"Experiment not found: {experiment_id}")

    return success_response(
        {
            "id": experiment.id,
            "name": experiment.name,
            "dataset_name": experiment.dataset_name,
        }
    )


@experiments_bp.route("/delete-experiment", methods=["DELETE"])
def delete_experiment():
    data = request.get_json()
    validate_required_fields(data, ["experiment_id"])

    experiment_id = data["experiment_id"]
    client = get_opik_api_client()

    client.experiments.delete_experiments_by_id(ids=[experiment_id])

    return success_response({"id": experiment_id})


@experiments_bp.route("/get-experiment-items", methods=["POST"])
def get_experiment_items():
    data = request.get_json()
    validate_required_fields(data, ["experiment_name"])

    experiment_name = data["experiment_name"]
    limit = data.get("limit", None)

    client = get_opik_api_client()

    try:
        data_bytes = b"".join(
            client.experiments.stream_experiment_items(
                experiment_name=experiment_name, request_options={"chunk_size": 100}
            )
        )
        lines = data_bytes.decode("utf-8").split("\r\n")
        items = [json.loads(line) for line in lines if line.strip()]

        if limit is not None:
            items = items[:limit]

        return success_response({"items": items})
    except Exception as e:
        if "NotFoundError" in str(type(e).__name__) or "404" in str(e):
            abort(404, f"Experiment not found: {experiment_name}")
        raise


@experiments_bp.route("/delete-experiment-items", methods=["DELETE"])
def delete_experiment_items():
    data = request.get_json()
    validate_required_fields(data, ["ids"])

    item_ids = data["ids"]
    client = get_opik_api_client()

    client.experiments.delete_experiment_items(ids=item_ids)

    return success_response({"deleted_count": len(item_ids)})
