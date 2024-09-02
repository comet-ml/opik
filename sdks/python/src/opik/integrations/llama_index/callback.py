from typing import Optional, Dict, List, Any

from llama_index.core.callbacks.base_handler import BaseCallbackHandler
from llama_index.core.callbacks.schema import CBEventType
from opik import Opik, Trace
from opik import opik_context
import uuid

from llama_index.core.callbacks.schema import (
    BASE_TRACE_EVENT,
    EventPayload,
)


class LlamaIndexCallbackHandler(BaseCallbackHandler):
    def __init__(
        self,
        event_starts_to_ignore: Optional[List[CBEventType]] = None,
        event_ends_to_ignore: Optional[List[CBEventType]] = None,
    ):
        event_starts_to_ignore = (
            event_starts_to_ignore if event_starts_to_ignore else []
        )
        event_ends_to_ignore = event_ends_to_ignore if event_ends_to_ignore else []
        super().__init__(
            event_starts_to_ignore=event_starts_to_ignore,
            event_ends_to_ignore=event_ends_to_ignore,
        )

        self._opik_client = Opik()
        self._opik_trace: Optional[Trace] = None

        self._map_event_id_to_span: Dict[str, Any] = {}
        self._map_event_id_to_output: Dict[str, Any] = {}

    def _create_trace(self, trace_id: Optional[str] = None) -> Trace:
        trace = self._opik_client.trace(
            name=trace_id, metadata={"created_from": "llama_index"}
        )
        return trace

    def start_trace(self, trace_id: Optional[str] = None) -> None:
        # When a new LLama Index trace is started, create a new trace in Opik
        existing_trace = opik_context.get_current_trace()
        if existing_trace:
            self._opik_trace = existing_trace
        else:
            self._opik_trace = self._create_trace(trace_id)

    def _get_last_event(self, trace_map: Dict[str, List[str]]) -> str:
        def dfs(key: str) -> str:
            if key not in trace_map or not trace_map[key]:
                return key
            return dfs(trace_map[key][-1])

        start_key = next(iter(trace_map))
        return dfs(start_key)

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        # When a trace finishes, we first get the last event output
        last_event = self._get_last_event(trace_map)
        last_event_output = self._map_event_id_to_output.get(last_event, None)

        # And then end the trace with the optional output
        if self._opik_trace:
            self._opik_trace.end(output=last_event_output)
            self._opik_trace = None

    def _get_span_output_from_event(
        self, event_type: CBEventType, payload: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        if payload is None:
            return None

        payload_copy = payload.copy()

        if EventPayload.SERIALIZED in payload_copy:
            # Always pop Serialized from payload as it may contain LLM api keys
            payload_copy.pop(EventPayload.SERIALIZED)

        if (
            event_type == CBEventType.EMBEDDING
            and EventPayload.EMBEDDINGS in payload_copy
        ):
            embeddings: Any = payload_copy.get(EventPayload.EMBEDDINGS)
            return {"num_embeddings": len(embeddings)}

        if (
            event_type == CBEventType.NODE_PARSING
            and EventPayload.NODES in payload_copy
        ):
            nodes = payload_copy.pop(EventPayload.NODES)
            payload_copy["num_nodes"] = len(nodes)
            return {"output": payload_copy}

        if event_type == CBEventType.CHUNKING and EventPayload.CHUNKS in payload:
            chunks = payload.pop(EventPayload.CHUNKS)
            payload_copy["num_chunks"] = len(chunks)

        if EventPayload.COMPLETION in payload:
            return {"output": payload_copy.get(EventPayload.COMPLETION)}

        if EventPayload.RESPONSE in payload:
            response: Any = payload_copy.get(EventPayload.RESPONSE)

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

    def _get_span_input_from_events(
        self, event_type: CBEventType, payload: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        if payload is None:
            return None

        payload_copy = payload.copy()

        if EventPayload.SERIALIZED in payload:
            # Remove
            payload_copy.pop(EventPayload.SERIALIZED)

        if event_type == CBEventType.EMBEDDING and EventPayload.CHUNKS in payload_copy:
            chunks = payload_copy.get(EventPayload.CHUNKS, [])
            return {"number_of_chunks": len(chunks)}

        if (
            event_type == CBEventType.NODE_PARSING
            and EventPayload.DOCUMENTS in payload_copy
        ):
            documents = payload_copy.pop(EventPayload.DOCUMENTS)
            payload_copy["documents"] = [doc.metadata for doc in documents]
            return payload_copy

        if EventPayload.QUERY_STR in payload_copy:
            return {EventPayload.QUERY_STR: payload_copy.get(EventPayload.QUERY_STR)}

        if EventPayload.MESSAGES in payload_copy:
            return {"messages": payload_copy.get(EventPayload.MESSAGES)}

        if EventPayload.PROMPT in payload_copy:
            return {"prompt": payload_copy.get(EventPayload.PROMPT)}

        if payload_copy:
            return {"input": payload_copy}
        else:
            return None

    def on_event_start(
        self,
        event_type: CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        parent_id: Optional[str] = None,
        **kwargs: Any,
    ) -> str:
        if not event_id:
            event_id = str(uuid.uuid4())

        # Under some scenarios, it is possible for `start_trace` to not be called (for example if
        # the callback raises an exception in a previous call).
        # Unclear what the best behavior is here, so for now we'll just create a new trace when
        if self._opik_trace is None:
            self._opik_trace = self._create_trace(trace_id=parent_id)

        # Get parent span Id if it exists
        if parent_id and parent_id in self._map_event_id_to_span:
            opik_parent_id = self._map_event_id_to_span[parent_id].id
        else:
            opik_parent_id = None

        # Compute the span input based on the event payload
        span_input = self._get_span_input_from_events(event_type, payload)

        # Create a new span for this event
        span = self._opik_client.span(
            trace_id=self._opik_trace.id,
            name=event_type.value,
            parent_span_id=opik_parent_id,
            type="llm" if event_type == CBEventType.LLM else "general",
            input=span_input,
        )
        self._map_event_id_to_span[event_id] = span

        # If the parent_id is a BASE_TRACE_EVENT, update the trace with the span input
        if parent_id == BASE_TRACE_EVENT and span_input:
            self._opik_trace.update(input=span_input)

        return event_id

    def on_event_end(
        self,
        event_type: CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        # Get the span output from the event and store it so we can use it if needed
        # when finishing the trace
        span_output = self._get_span_output_from_event(event_type, payload)
        if event_id:
            self._map_event_id_to_output[event_id] = span_output

            # Log the output to the span with the matching id
            span = self._map_event_id_to_span[event_id]
            if span:
                span.end(output=span_output)
