# Setting up a demo project
#
# Evaluation traces & spans
# We start with evaluation so it shows up at the bottom.
# The evaluation is going to be tracked into a separate project from the demo traces.
# It was run using a simple context with 3 sentences, and 3 questions asking about it.

import opik 
import uuid6
import json
import urllib.request
import uuid6
import logging

import opik.rest_api
from opik_backend.demo_data import evaluation_traces, evaluation_spans, demo_traces, demo_spans

logger = logging.getLogger(__name__)

UUID_MAP = {}

def make_http_request(base_url, message, workspace_name, comet_api_key):
    try:
        headers = {}
        if workspace_name:
            headers["Comet-Workspace"] = workspace_name

        if comet_api_key:
            headers["authorization"] = f"{comet_api_key}"

        url = base_url + message["url"]
        data = json.dumps(message["payload"]).encode("utf-8") if "payload" in message else None

        req = urllib.request.Request(url, data=data, method=message["method"])
        for key, value in headers.items():
            req.add_header(key, value)
        req.add_header("Content-Type", "application/json")

        with urllib.request.urlopen(req) as response:
            status_code = response.getcode()
            logger.info("Got response status %s, from method %s on url %s", status_code, message["method"], url)
            body = response.read()
            if body:
                data = json.loads(body)
            else:
                data = None
            return data, status_code
    except urllib.error.HTTPError as e:
        if e.code >= 500:
            raise e
        logger.info("Got error %s, from method %s on url %s", e.code, message["method"], url)
        return None, e.code
    
def create_feedback_scores_definition(base_url, workspace_name, comet_api_key):

    name = "User feedback"
    params = {
        "name": name
    }
    request = {
        "url": f"/v1/private/feedback-definitions?{urllib.parse.urlencode(params)}",
        "method": "GET"
    }

    data, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)

    if status_code == 200 and data["content"]:
        for definition in data["content"]:
            if definition["name"] == name:
                logger.info("Feedback definition already exists")
                return

    request = {
        "url": "/v1/private/feedback-definitions",
        "method": "POST",
        "payload": {
            "name": name,
            "description": "Feedback provided by the user",
            "type": "categorical",
            "details": {
                "categories":{ 
                    "👍": 1.0,
                    "👎": 0.0 
                }
            },
        },
    }

    make_http_request(base_url, request, workspace_name, comet_api_key)

def project_exists(base_url, workspace_name, comet_api_key, project_name):
    request = {
        "url": "/v1/private/projects/retrieve",
        "method": "POST",
        "payload": {
            "name": project_name,
        },
    }

    _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    return status_code == 200

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

def create_demo_evaluation_project(base_url: str, workspace_name, comet_api_key):
    client: opik.Opik = None
    try:
        project_name = "Demo evaluation"
        if project_exists(base_url, workspace_name, comet_api_key, project_name):
            logger.info("%s project already exists", project_name)
            return
        
        client = opik.Opik(
            project_name=project_name,
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

    except Exception as e:
        logger.error(e)
    finally:
        # Close the client
        if client:
            client.flush()
            client.end()

def create_demo_chatbot_project(base_url: str, workspace_name, comet_api_key):
    client: opik.Opik = None

    try:
        project_name = "Demo chatbot 🤖"
        if project_exists(base_url, workspace_name, comet_api_key, project_name):
            logger.info("%s project already exists", project_name)
            return

        # Demo traces and spans
        # We have a simple chatbot application built using llama-index.
        # We gave it the content of Opik documentation as context, and then asked it a few questions.

        client = opik.Opik(
            project_name=project_name,
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

    except Exception as e:
        logger.error(e)
    finally:
        # Close the client
        if client:
            client.flush()
            client.end()

def create_demo_data(base_url: str, workspace_name, comet_api_key):

    try:        
        create_demo_evaluation_project(base_url, workspace_name, comet_api_key)

        create_demo_chatbot_project(base_url, workspace_name, comet_api_key)

        create_feedback_scores_definition(base_url, workspace_name, comet_api_key)

        logger.info("Demo data created successfully")
    except Exception as e:
        logger.error(e)
