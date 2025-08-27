import pytest
import httpx
from opik.api_objects.opik_client import Opik  # adjust if path differs

def fetch_experiment_httpx(exp_id: str, workspace="default"):
    """Directly fetch experiment from API (like your working curl)."""
    url = f"http://localhost:8080/v1/private/experiments/{exp_id}"
    params = {"workspace_name": workspace}
    headers = {"Content-Type": "application/json"}
    resp = httpx.get(url, headers=headers, params=params)
    resp.raise_for_status()
    return resp.json()

def test_update_experiment_live():
    client = Opik(project_name="test-project", workspace="default")

    exp = client.create_experiment(dataset_name="my-dataset", name="original-exp")

    client.update_experiment(exp.id, "new-nafrfme", {"k": "v", "num": 1})

    updated = fetch_experiment_httpx(exp.id)

    print("Fetched experiment:", updated)
    print("ID:", updated["id"])
    print("Name:", updated["name"])

    assert updated["id"] == exp.id
    assert updated["name"] == "new-nafrfme"
    assert updated["metadata"] == {"k": "v", "num": 1}

if __name__ == "__main__":
    test_update_experiment_live()
