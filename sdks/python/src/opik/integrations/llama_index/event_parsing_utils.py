from typing import Optional, Dict, Any
from llama_index.core.callbacks import schema as llama_index_schema


def get_span_input_from_events(
    event_type: llama_index_schema.CBEventType, payload: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    if payload is None:
        return None

    payload_copy = payload.copy()

    if llama_index_schema.EventPayload.SERIALIZED in payload:
        # Remove
        payload_copy.pop(llama_index_schema.EventPayload.SERIALIZED)

    if (
        event_type == llama_index_schema.CBEventType.EMBEDDING
        and llama_index_schema.EventPayload.CHUNKS in payload_copy
    ):
        chunks = payload_copy.get(llama_index_schema.EventPayload.CHUNKS, [])
        return {"number_of_chunks": len(chunks)}

    if (
        event_type == llama_index_schema.CBEventType.NODE_PARSING
        and llama_index_schema.EventPayload.DOCUMENTS in payload_copy
    ):
        documents = payload_copy.pop(llama_index_schema.EventPayload.DOCUMENTS)
        payload_copy["documents"] = [doc.metadata for doc in documents]
        return payload_copy

    if llama_index_schema.EventPayload.QUERY_STR in payload_copy:
        return {
            llama_index_schema.EventPayload.QUERY_STR: payload_copy.get(
                llama_index_schema.EventPayload.QUERY_STR
            )
        }

    if llama_index_schema.EventPayload.MESSAGES in payload_copy:
        return {"messages": payload_copy.get(llama_index_schema.EventPayload.MESSAGES)}

    if llama_index_schema.EventPayload.PROMPT in payload_copy:
        return {"prompt": payload_copy.get(llama_index_schema.EventPayload.PROMPT)}

    if payload_copy:
        return {"input": payload_copy}
    else:
        return None


def get_span_output_from_event(
    event_type: llama_index_schema.CBEventType, payload: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    if payload is None:
        return None

    payload_copy = payload.copy()

    if llama_index_schema.EventPayload.SERIALIZED in payload_copy:
        # Always pop Serialized from payload as it may contain LLM api keys
        payload_copy.pop(llama_index_schema.EventPayload.SERIALIZED)

    if (
        event_type == llama_index_schema.CBEventType.EMBEDDING
        and llama_index_schema.EventPayload.EMBEDDINGS in payload_copy
    ):
        embeddings: Any = payload_copy.get(llama_index_schema.EventPayload.EMBEDDINGS)
        return {"num_embeddings": len(embeddings)}

    if (
        event_type == llama_index_schema.CBEventType.NODE_PARSING
        and llama_index_schema.EventPayload.NODES in payload_copy
    ):
        nodes = payload_copy.pop(llama_index_schema.EventPayload.NODES)
        payload_copy["num_nodes"] = len(nodes)
        return {"output": payload_copy}

    if (
        event_type == llama_index_schema.CBEventType.CHUNKING
        and llama_index_schema.EventPayload.CHUNKS in payload
    ):
        chunks = payload.pop(llama_index_schema.EventPayload.CHUNKS)
        payload_copy["num_chunks"] = len(chunks)

    if llama_index_schema.EventPayload.COMPLETION in payload:
        return {"output": payload_copy.get(llama_index_schema.EventPayload.COMPLETION)}

    if llama_index_schema.EventPayload.RESPONSE in payload:
        response: Any = payload_copy.get(llama_index_schema.EventPayload.RESPONSE)

        # Skip streaming responses as consuming them would block the user's execution path
        if "Streaming" in type(response).__name__:
            return None

        if hasattr(response, "response"):
            return {"output": response.response}

        if hasattr(response, "message"):
            output = dict(response.message)
            if "additional_kwargs" in output:
                if "tool_calls" in output["additional_kwargs"]:
                    output["tool_calls"] = output["additional_kwargs"]["tool_calls"]

                del output["additional_kwargs"]

            return {"output": output}

    if payload_copy:
        return {"output": payload_copy}
    else:
        return None
