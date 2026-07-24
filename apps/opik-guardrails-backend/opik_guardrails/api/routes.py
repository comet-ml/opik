import json
import logging
import os
import re
import subprocess
import sys
import threading

import flask
import pydantic

from opik_guardrails import schemas
from opik_guardrails.services import validation_engine
from opik_guardrails.services.custom_training import status as training_status

LOGGER = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint(
    "guardrails", __name__, url_prefix="/api/v1/guardrails"
)

_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")

# Serializes the check-and-launch of training jobs. The server runs as a single
# gunicorn worker, so an in-process lock is enough to make claiming a name atomic.
_TRAIN_LOCK = threading.Lock()


def _adapters_dir() -> str:
    return os.getenv("OPIK_GUARDRAILS_ADAPTERS_DIR") or "/adapters"


@guardrails_blueprint.errorhandler(pydantic.ValidationError)
def handle_validation_error(
    error: pydantic.ValidationError,
) -> tuple[flask.Response, int]:
    LOGGER.warning(f"Validation error: {error.errors()}")
    return flask.jsonify({"error": "Invalid input", "details": error.errors()}), 400


@guardrails_blueprint.errorhandler(Exception)
def handle_generic_exception(error: Exception) -> tuple[flask.Response, int]:
    LOGGER.error(f"Validation failed: {str(error)}", exc_info=True)
    return flask.jsonify({"error": f"Validation failed: {str(error)}"}), 500


@guardrails_blueprint.route("/validations", methods=["POST"])
def validate_combined() -> tuple[flask.Response, int]:
    if flask.request.method != "POST":
        return flask.jsonify({"error": "Method not allowed"}), 405

    data = flask.request.get_json()
    request_with_validated_text_and_type = schemas.GuardrailsValidationRequest(**data)

    all_validations_passed = True
    validation_results = []

    for validation_descriptor in request_with_validated_text_and_type.validations:
        validated_config = validation_engine.build_validation_config_from_raw_dict(
            validation_type=validation_descriptor.type,
            config_dict=validation_descriptor.config,
        )
        validation_result = validation_engine.run_validator(
            validation_type=validation_descriptor.type,
            text=request_with_validated_text_and_type.text,
            config=validated_config,
        )

        validation_results.append(validation_result.model_dump(serialize_as_any=True))

        if not validation_result.validation_passed:
            all_validations_passed = False

    return flask.jsonify(
        {
            "validation_passed": all_validations_passed,
            "validations": validation_results,
        }
    ), 200


@guardrails_blueprint.route("/custom/train", methods=["POST"])
def train_custom_guardrail() -> tuple[flask.Response, int]:
    request = schemas.CustomGuardrailTrainingRequest(**flask.request.get_json())

    if not _NAME_PATTERN.match(request.name):
        return flask.jsonify(
            {"error": "Invalid name; use letters, digits, '.', '_' or '-' only"}
        ), 400

    model_dir = os.path.join(_adapters_dir(), request.name)
    status_path = os.path.join(model_dir, "status.json")

    # Claim the name atomically: only one thread can pass the checks and write the
    # "training" status before launching, so concurrent requests can't both start.
    with _TRAIN_LOCK:
        existing = training_status.read(status_path).get("status")
        if existing == "training":
            return flask.jsonify(
                {"error": f"A training run for '{request.name}' is already in progress"}
            ), 409
        if existing == "completed" and not request.overwrite:
            return flask.jsonify(
                {
                    "error": f"A guardrail named '{request.name}' already exists; "
                    f"pass overwrite=true to retrain it"
                }
            ), 409

        os.makedirs(model_dir, exist_ok=True)

        job = {
            "name": request.name,
            "description": request.description,
            "base_model": request.base_model,
            "output_dir": _adapters_dir(),
            "examples": [e.model_dump() for e in request.examples],
            "config": {
                "epochs": request.epochs,
                "batch_size": 16,
                "lr": 2e-4,
                "lora_r": 16,
                "lora_alpha": 16,
                "max_len": 512,
                "val_fraction": 0.1,
                "test_fraction": 0.15,
                "seed": 42,
            },
        }
        with open(os.path.join(model_dir, "_job.json"), "w") as f:
            json.dump(job, f)

        training_status.write(status_path, "training")

        subprocess.Popen(
            [
                sys.executable,
                "-m",
                "opik_guardrails.services.custom_training.run",
                os.path.join(model_dir, "_job.json"),
            ]
        )

    return flask.jsonify({"name": request.name, "status": "training"}), 202


@guardrails_blueprint.route("/custom/train/<name>", methods=["GET"])
def get_custom_guardrail_training(name: str) -> tuple[flask.Response, int]:
    if not _NAME_PATTERN.match(name):
        return flask.jsonify({"error": "Invalid name"}), 400

    current = training_status.read(os.path.join(_adapters_dir(), name, "status.json"))
    if not current:
        return flask.jsonify({"error": f"No training found for '{name}'"}), 404

    return flask.jsonify({"name": name, **current}), 200


healthcheck = flask.Blueprint("healthcheck", __name__)


@healthcheck.route("/healthcheck", methods=["GET"])
def health() -> tuple[str, int]:
    return "OK", 200
