# Setting up a demo project
#
# Evaluation traces & spans
# We start with evaluation so it shows up at the bottom.
# The evaluation is going to be tracked into a separate project from the demo traces.
# It was run using a simple context with 3 sentences, and 3 questions asking about it.

import opik
import uuid6
from demo_data import evaluation_traces, evaluation_spans, demo_traces, demo_spans

UUID_MAP = {}


def get_new_uuid(old_id):
    """
    The demo_data has the IDs hardcoded in, to preserve the relationships between the traces and spans.
    However, we need to generate unique ones before logging them.
    """
    if old_id in UUID_MAP:
        new_id = UUID_MAP[old_id]
    else:
        new_id = str(uuid6.uuid7())
        UUID_MAP[old_id] = new_id
    return new_id


def create_demo_data(base_url: str, workspace_name, comet_api_key):
    client = opik.Opik(
        project_name="Demo evaluation",
        workspace=workspace_name,
        host=base_url,
        api_key=comet_api_key,
        _use_batching=True,
    )

    for trace in sorted(evaluation_traces, key=lambda x: x["start_time"]):
        new_id = get_new_uuid(trace["id"])
        trace["id"] = new_id
        client.trace(**trace)

    for span in sorted(evaluation_spans, key=lambda x: x["start_time"]):
        new_id = get_new_uuid(span["id"])
        span["id"] = new_id
        new_trace_id = get_new_uuid(span["trace_id"])
        span["trace_id"] = new_trace_id
        if "parent_span_id" in span:
            new_parent_span_id = get_new_uuid(span["parent_span_id"])
            span["parent_span_id"] = new_parent_span_id
        client.span(**span)

    client.flush()

    # Demo traces and spans
    # We have a simple chatbot application built using llama-index.
    # We gave it the content of Opik documentation as context, and then asked it a few questions.

    client = opik.Opik(
        project_name="Demo chatbot 🤖",
        workspace=workspace_name,
        host=base_url,
        api_key=comet_api_key,
        _use_batching=True,
    )

    for trace in sorted(demo_traces, key=lambda x: x["start_time"]):
        new_id = get_new_uuid(trace["id"])
        trace["id"] = new_id
        client.trace(**trace)

    for span in sorted(demo_spans, key=lambda x: x["start_time"]):
        new_id = get_new_uuid(span["id"])
        span["id"] = new_id
        new_trace_id = get_new_uuid(span["trace_id"])
        span["trace_id"] = new_trace_id
        if "parent_span_id" in span:
            new_parent_span_id = get_new_uuid(span["parent_span_id"])
            span["parent_span_id"] = new_parent_span_id
        client.span(**span)

    # Prompts
    # We now create 3 versions of a Q&A prompt. The final version is from llama-index.

    client.create_prompt(
        name="Q&A Prompt",
        prompt="""Answer the query using your prior knowledge.
    Query: {{query_str}}
    Answer:
    """,
    )

    client.create_prompt(
        name="Q&A Prompt",
        prompt="""Here is the context information.
    -----------------
    {{context_str}}
    -----------------
    Answer the query using the given context and not prior knowledge.

    Query: {{query_str}}
    Answer:
    """,
    )

    client.create_prompt(
        name="Q&A Prompt",
        prompt="""You are an expert Q&A system that is trusted around the world.
    Always answer the query using the provided context information, and not prior knowledge.
    Some rules to follow:
    1. Never directly reference the given context in your answer.
    2. Avoid statements like 'Based on the context, ...' or 'The context information ...' or anything along those lines.

    Context information is below.
    ---------------------
    {{context_str}}
    ---------------------
    Given the context information and not prior knowledge, answer the query.
    Query: {{query_str}}
    Answer:
    """,
    )

    # Dataset

    dataset = client.get_or_create_dataset(name="Demo dataset")
    dataset.insert(
        [
            {"input": "What is the best LLM evaluation tool?"},
            {"input": "What is the easiest way to start with Opik?"},
            {"input": "Is Opik open source?"},
        ]
    )

    # In addition to creating the dataset, we also create a mapping from the dataset items to the traces. This will be handy for creating the experiment.

    items = dataset.get_items()
    dataset_id_map = {item["input"]: item["id"] for item in items}

    # Experiment
    # The experiment is constructed by joining the traces with the dataset items.

    experiment = client.create_experiment(
        name="Demo experiment", dataset_name="Demo dataset"
    )
    experiment_items = []

    for trace in evaluation_traces:
        trace_id = trace["id"]
        dataset_item_id = dataset_id_map.get(trace.get("input", {}).get("input", " "))
        if dataset_item_id is not None:
            experiment_items.append(
                opik.api_objects.experiment.experiment_item.ExperimentItemReferences(
                    dataset_item_id=dataset_item_id, trace_id=trace_id
                )
            )

    experiment.insert(experiment_items)

    client.flush()


if __name__ == "__main__":
    base_url = "http://localhost:5173/api"
    workspace_name = None
    comet_api_key = None

    create_demo_data(base_url, workspace_name, comet_api_key)
