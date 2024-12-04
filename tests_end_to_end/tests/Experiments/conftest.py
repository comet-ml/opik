import pytest
import os
from opik import Opik
from playwright.sync_api import Page
from page_objects.DatasetsPage import DatasetsPage
from page_objects.DatasetItemsPage import DatasetItemsPage
from page_objects.ExperimentsPage import ExperimentsPage
from opik.evaluation.metrics import Contains
from opik.evaluation import evaluate
from sdk_helpers import delete_experiment_by_id
import json


def eval_task(item: dict):
    return {
        'input': item['input'],
        'output': item['output'],
        'reference': 'output'
    }

@pytest.fixture()
def mock_experiment(client: Opik, create_delete_dataset_sdk, insert_dataset_items_sdk):
    dataset = client.get_dataset(create_delete_dataset_sdk)
    experiment_name='test_experiment'
    eval = evaluate(
        experiment_name=experiment_name,
        dataset=dataset,
        task=eval_task,
        scoring_metrics=[Contains()]
    )
    yield {
        'id': eval.experiment_id,
        'name': experiment_name
    }
    try:
        delete_experiment_by_id(eval.experiment_id)
    except Exception as e:
        print(f'Experiment cleanup error: {e}')
        pass
    