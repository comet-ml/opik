from typing import Optional, TYPE_CHECKING

from . import invoke_agent_decorator
from .converse import chunks_aggregator as converse_chunks_aggregator
from .converse import converse_decorator

from .invoke_model import invoke_model_decorator
from .invoke_model import chunks_aggregator as invoke_model_chunks_aggregator


if TYPE_CHECKING:
    from mypy_boto3_bedrock_runtime.client import BedrockRuntimeClient


def track_bedrock(
    client: "BedrockRuntimeClient",
    project_name: Optional[str] = None,
) -> "BedrockRuntimeClient":
    """Adds Opik tracking to an AWS Bedrock client.

    Tracks calls to `converse()`, `converse_stream()`, `invoke_model()`, and `invoke_model_with_response_stream()` methods.
    Can be used within other Opik-tracked functions.

    Supported Model subproviders for InvokeModel API (both streaming and non-streaming):
    - **Anthropic** (Claude)
    - **Amazon** (Nova)
    - **Meta** (Llama)
    - **Mistral** (Pixtral)

    Args:
        client: An instance of an AWS Bedrock client (botocore.client.BedrockRuntime or botocore.client.AgentsforBedrockRuntime).
        project_name: The name of the project to log data.

    Returns:
        The modified bedrock client with Opik tracking enabled.
    """

    decorator_for_converse = converse_decorator.BedrockConverseDecorator()
    decorator_for_invoke_agent = invoke_agent_decorator.BedrockInvokeAgentDecorator()
    decorator_for_invoke_model = invoke_model_decorator.BedrockInvokeModelDecorator()

    if hasattr(client, "invoke_agent") and not hasattr(
        client.invoke_agent, "opik_tracked"
    ):
        wrapper = decorator_for_invoke_agent.track(
            type="llm",
            name="bedrock_invoke_agent",
            project_name=project_name,
            generations_aggregator=converse_chunks_aggregator.aggregate_invoke_agent_chunks,
        )
        tracked_invoke_agent = wrapper(client.invoke_agent)
        client.invoke_agent = tracked_invoke_agent

    if hasattr(client, "converse") and not hasattr(client.converse, "opik_tracked"):
        wrapper = decorator_for_converse.track(
            type="llm",
            name="bedrock_converse",
            project_name=project_name,
        )
        tracked_converse = wrapper(client.converse)
        client.converse = tracked_converse

    if hasattr(client, "converse_stream") and not hasattr(
        client.converse_stream, "opik_tracked"
    ):
        stream_wrapper = decorator_for_converse.track(
            type="llm",
            name="bedrock_converse_stream",
            project_name=project_name,
            generations_aggregator=converse_chunks_aggregator.aggregate_converse_stream_chunks,
        )
        tracked_converse_stream = stream_wrapper(client.converse_stream)
        client.converse_stream = tracked_converse_stream

    if hasattr(client, "invoke_model") and not hasattr(
        client.invoke_model, "opik_tracked"
    ):
        wrapper = decorator_for_invoke_model.track(
            type="llm",
            name="bedrock_invoke_model",
            project_name=project_name,
        )
        tracked_invoke_model = wrapper(client.invoke_model)
        client.invoke_model = tracked_invoke_model

    if hasattr(client, "invoke_model_with_response_stream") and not hasattr(
        client.invoke_model_with_response_stream, "opik_tracked"
    ):
        stream_wrapper = decorator_for_invoke_model.track(
            type="llm",
            name="bedrock_invoke_model_stream",
            project_name=project_name,
            generations_aggregator=invoke_model_chunks_aggregator.aggregate_chunks_to_dataclass,
        )
        tracked_invoke_model_stream = stream_wrapper(
            client.invoke_model_with_response_stream
        )
        client.invoke_model_with_response_stream = tracked_invoke_model_stream

    return client
