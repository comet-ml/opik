import time
from typing import Any, Callable, Dict, List, Optional

import httpx

import opik.exceptions as exceptions
from opik.api_objects import opik_client

from . import rest_api_client


def create_custom_guardrail(
    name: str,
    description: str,
    examples: List[Dict[str, Any]],
    base_model: str = "Qwen/Qwen2.5-1.5B-Instruct",
    epochs: float = 3.0,
    overwrite: bool = False,
    wait: bool = True,
    poll_interval: float = 10.0,
    timeout: float = 3600.0,
    callback: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> Dict[str, Any]:
    """
    Train a custom binary guardrail on the guardrails server and make it available
    to the :class:`~opik.guardrails.CustomGuardrail` guard.

    Args:
        name: Name of the model, used later to reference the guardrail.
        description: Natural-language metric, completing "Determine whether it ...",
            for example "contains toxic or abusive language".
        examples: Labeled examples as ``{"text": ..., "label": 0 or 1}``, where 1 means
            the metric holds (the guardrail should fail).
        base_model: Base model to fine-tune the adapter on.
        epochs: Number of training epochs.
        overwrite: If True, retrain and replace an existing guardrail with this name.
            If False (default), a name that already exists is rejected.
        wait: If True, block until training completes and return the final status.
        poll_interval: Seconds between status checks while waiting.
        timeout: Maximum seconds to wait for training to complete.
        callback: Optional function called once per poll with the current status,
            which carries a ``progress`` dict (percent, epoch, train_loss, latest_eval)
            while training. Only used when ``wait`` is True.

    Returns:
        The training status. When ``wait`` is True this includes the eval metrics.

    Raises:
        opik.exceptions.GuardrailTrainingError: If training fails or does not complete
            within ``timeout``.
    """
    client = opik_client.get_global_client()
    api_client = rest_api_client.GuardrailsApiClient(
        httpx_client=httpx.Client(timeout=60),
        host_url=client.config.guardrails_backend_host,
    )

    result = api_client.train_custom(
        name=name,
        description=description,
        examples=examples,
        base_model=base_model,
        epochs=epochs,
        overwrite=overwrite,
    )

    if not wait:
        return result

    deadline = time.time() + timeout
    while time.time() < deadline:
        status = api_client.get_custom_training_status(name)
        if callback is not None:
            callback(status)
        state = status.get("status")
        if state == "completed":
            return status
        if state == "failed":
            raise exceptions.GuardrailTrainingError(
                f"Custom guardrail '{name}' training failed: {status.get('error')}"
            )
        time.sleep(poll_interval)

    raise exceptions.GuardrailTrainingError(
        f"Custom guardrail '{name}' training did not complete within {timeout} seconds"
    )
