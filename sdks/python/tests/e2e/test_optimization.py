import opik
import pytest
from opik.rest_api import core as rest_api_core
from . import verifiers


def test_optimization_lifecycle__happyflow(opik_client: opik.Opik, dataset_name: str):
    dataset = opik_client.create_dataset(dataset_name)

    # Create optimization
    optimization = opik_client.create_optimization(
        objective_name="some-objective-name",
        dataset_name=dataset.name,
        name="some-optimization-name",
    )

    verifiers.verify_optimization(
        opik_client=opik_client,
        optimization_id=optimization.id,
        name="some-optimization-name",
        dataset_name=dataset.name,
        status="running",
        objective_name="some-objective-name",
    )

    # Update optimization name and status
    optimization.update(name="new-optimization-name", status="completed")
    verifiers.verify_optimization(
        opik_client=opik_client,
        optimization_id=optimization.id,
        name="new-optimization-name",
        dataset_name=dataset.name,
        status="completed",
        objective_name="some-objective-name",
    )

    opik_client.delete_optimizations([optimization.id])

    with pytest.raises(rest_api_core.ApiError):
        opik_client.get_optimization_by_id(optimization.id)
