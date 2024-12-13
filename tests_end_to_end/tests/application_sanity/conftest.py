import pytest
import os
import opik
import yaml
import json
from opik.evaluation import evaluate
from opik.evaluation.metrics import Contains, Equals
from opik import opik_context, track


@pytest.fixture(scope="session", autouse=True)
def config():
    curr_dir = os.path.dirname(__file__)
    config_path = os.path.join(curr_dir, "sanity_config.yaml")

    with open(config_path, "r") as f:
        conf = yaml.safe_load(f)

    os.environ["OPIK_PROJECT_NAME"] = conf["project"]["name"]
    return conf


@pytest.fixture(scope="session", autouse=True)
def client_sanity(config):
    return opik.Opik(
        project_name=config["project"]["name"],
        workspace="default",
        host="http://localhost:5173/api",
    )


@pytest.fixture(scope="module")
def log_traces_and_spans_low_level(client_sanity, config):
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """

    trace_config = {
        "count": config["traces"]["count"],
        "prefix": config["traces"]["client"]["prefix"],
        "tags": config["traces"]["client"]["tags"],
        "metadata": config["traces"]["client"]["metadata"],
        "feedback_scores": [
            {"name": key, "value": value}
            for key, value in config["traces"]["client"]["feedback-scores"].items()
        ],
    }

    span_config = {
        "count": config["spans"]["count"],
        "prefix": config["spans"]["client"]["prefix"],
        "tags": config["spans"]["client"]["tags"],
        "metadata": config["spans"]["client"]["metadata"],
        "feedback_scores": [
            {"name": key, "value": value}
            for key, value in config["spans"]["client"]["feedback-scores"].items()
        ],
    }

    for trace_index in range(trace_config["count"]):
        client_trace = client_sanity.trace(
            name=trace_config["prefix"] + str(trace_index),
            input={"input": f"input-{trace_index}"},
            output={"output": f"output-{trace_index}"},
            tags=trace_config["tags"],
            metadata=trace_config["metadata"],
            feedback_scores=trace_config["feedback_scores"],
        )
        for span_index in range(span_config["count"]):
            client_span = client_trace.span(
                name=span_config["prefix"] + str(span_index),
                input={"input": f"input-{span_index}"},
                output={"output": f"output-{span_index}"},
                tags=span_config["tags"],
                metadata=span_config["metadata"],
            )
            for score in span_config["feedback_scores"]:
                client_span.log_feedback_score(name=score["name"], value=score["value"])


@pytest.fixture(scope="module")
def log_traces_and_spans_decorator(config):
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """

    trace_config = {
        "count": config["traces"]["count"],
        "prefix": config["traces"]["decorator"]["prefix"],
        "tags": config["traces"]["decorator"]["tags"],
        "metadata": config["traces"]["decorator"]["metadata"],
        "feedback_scores": [
            {"name": key, "value": value}
            for key, value in config["traces"]["decorator"]["feedback-scores"].items()
        ],
    }

    span_config = {
        "count": config["spans"]["count"],
        "prefix": config["spans"]["decorator"]["prefix"],
        "tags": config["spans"]["decorator"]["tags"],
        "metadata": config["spans"]["decorator"]["metadata"],
        "feedback_scores": [
            {"name": key, "value": value}
            for key, value in config["spans"]["decorator"]["feedback-scores"].items()
        ],
    }

    @track()
    def make_span(x):
        opik_context.update_current_span(
            name=span_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=span_config["metadata"],
            tags=span_config["tags"],
            feedback_scores=span_config["feedback_scores"],
        )
        return {"output": f"output-{x}"}

    @track()
    def make_trace(x):
        for spans_no in range(span_config["count"]):
            make_span(spans_no)

        opik_context.update_current_trace(
            name=trace_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=trace_config["metadata"],
            tags=trace_config["tags"],
            feedback_scores=trace_config["feedback_scores"],
        )
        return {"output": f"output-{x}"}

    for x in range(trace_config["count"]):
        make_trace(x)


@pytest.fixture(scope="module")
def dataset(config, client_sanity):
    dataset_config = {
        "name": config["dataset"]["name"],
        "filename": config["dataset"]["filename"],
    }
    dataset = client_sanity.get_or_create_dataset(dataset_config["name"])

    curr_dir = os.path.dirname(__file__)
    dataset_filepath = os.path.join(curr_dir, dataset_config["filename"])
    dataset.read_jsonl_from_file(dataset_filepath)

    return dataset


@pytest.fixture(scope="module")
def create_experiments(config, dataset):
    exp_config = {
        "prefix": config["experiments"]["prefix"],
        "metrics": config["experiments"]["metrics"],
        "dataset_name": config["experiments"]["dataset-name"],
    }

    def eval_contains(x):
        return {
            "input": x["input"],
            "output": x["expected_output"],
            "reference": "hello",
        }

    def eval_equals(x):
        return {
            "input": x["input"],
            "output": x["expected_output"],
            "reference": "goodbye",
        }

    contains_metric = Contains(name="Contains", case_sensitive=False)
    equals_metric = Equals(name="Equals", case_sensitive=False)

    evaluate(
        experiment_name=exp_config["prefix"] + "Contains",
        dataset=dataset,
        task=eval_contains,
        scoring_metrics=[contains_metric],
    )

    evaluate(
        experiment_name=exp_config["prefix"] + "Equals",
        dataset=dataset,
        task=eval_equals,
        scoring_metrics=[equals_metric],
    )


@pytest.fixture(scope="function")
def dataset_content(config):
    curr_dir = os.path.dirname(__file__)
    dataset_filepath = os.path.join(curr_dir, config["dataset"]["filename"])

    data = []
    with open(dataset_filepath, "r") as f:
        for line in f:
            data.append(json.loads(line))
    return data
