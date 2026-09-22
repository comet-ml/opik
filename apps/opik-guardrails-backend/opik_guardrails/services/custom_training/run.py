"""Subprocess entrypoint for a single custom guardrail training job.

Invoked as ``python -m opik_guardrails.services.custom_training.run <job.json>``.
Runs training out of the Flask request process and records progress in a
``status.json`` next to the trained model so the API can be polled.
"""

import json
import os
import sys
import traceback

from . import status, trainer


def main() -> None:
    job_path = sys.argv[1]
    with open(job_path) as f:
        job = json.load(f)

    model_dir = os.path.join(job["output_dir"], job["name"])
    status_path = os.path.join(model_dir, "status.json")

    status.write(status_path, "training")
    try:
        result = trainer.train_and_save(
            examples=job["examples"],
            name=job["name"],
            description=job["description"],
            base_model=job["base_model"],
            output_dir=job["output_dir"],
            config=job["config"],
            status_path=status_path,
        )
        status.write(status_path, "completed", eval_metrics=result["metrics"])
    except Exception as e:
        status.write(status_path, "failed", error=str(e))
        traceback.print_exc()
        raise


if __name__ == "__main__":
    main()
