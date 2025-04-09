"""FastAPI server for forwarding requests to Ollama."""

from fastapi import FastAPI, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from typing import AsyncGenerator, Dict, Callable, Any
import json
import logging
from openai import AsyncOpenAI, APIError, APIConnectionError, APITimeoutError
import time
from rich.logging import RichHandler
from rich.console import Console
import uuid

# Configure rich console
console = Console()

# Create a new logger for the forwarding server
forward_logger = logging.getLogger("forward-server")
forward_logger.setLevel(logging.INFO)

# Remove any existing handlers
for handler in forward_logger.handlers:
    forward_logger.removeHandler(handler)

# Add rich handler
rich_handler = RichHandler(
    rich_tracebacks=True,
    omit_repeated_times=False,
    show_path=False,
    show_time=True,
    markup=True,
)
rich_handler.setFormatter(logging.Formatter("%(message)s"))
forward_logger.addHandler(rich_handler)


def log_response(
    method: str, url: str, status: int, duration: float, request_id: str
) -> None:
    """Log response with color formatting."""
    method_color = {
        "GET": "green",
        "POST": "yellow",
        "PUT": "blue",
        "DELETE": "red",
    }.get(method, "white")

    status_color = {2: "green", 3: "yellow", 4: "red", 5: "red bold"}.get(
        status // 100, "white"
    )

    forward_logger.info(
        f"[dim]{request_id}[/] - [{method_color}]{method}[/] {url} [{status_color}]{status}[/]  ({duration:.2f}s)"
    )


async def log_request_middleware(
    request: Request, call_next: Callable[[Request], Any]
) -> Any:
    """Middleware to log request details."""
    start_time = time.time()
    request_id = str(uuid.uuid4())[:8]
    request.state.request_id = request_id

    # Log request details
    method = request.method
    url = request.url.path
    query_params = request.url.query
    method_color = {
        "GET": "green",
        "POST": "yellow",
        "PUT": "blue",
        "DELETE": "red",
    }.get(method, "white")

    forward_logger.info(
        f"[dim]{request_id}[/] - [{method_color}]{method}[/] {url}{f' ?{query_params}' if query_params else ''}"
    )

    response = await call_next(request)

    if isinstance(response, StreamingResponse):
        original_iterator = response.body_iterator

        async def logged_iterator() -> AsyncGenerator[bytes, None]:
            try:
                async for chunk in original_iterator:
                    yield chunk
            finally:
                duration = time.time() - start_time
                log_response(
                    request.method,
                    request.url.path,
                    response.status_code,
                    duration,
                    request_id,
                )

        response.body_iterator = logged_iterator()
    else:
        duration = time.time() - start_time
        log_response(
            request.method, request.url.path, response.status_code, duration, request_id
        )

    return response


async def stream_generator(
    stream: AsyncGenerator[Any, None],
) -> AsyncGenerator[str, None]:
    """Convert OpenAI stream to SSE format."""
    async for chunk in stream:
        yield f"data: {json.dumps(chunk.model_dump())}\n\n"


def create_app(llm_server_host: str) -> FastAPI:
    app = FastAPI(title="Opik Ollama Proxy")

    # Add middleware
    app.middleware("http")(log_request_middleware)

    # Configure CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "https://dev.comet.com",
            "https://staging.dev.comet.com",
            "https://comet.com",
            "http://localhost:5173",
            "https://www.comet.com",
            "http://localhost:5174",
        ],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # Create OpenAI client
    client = AsyncOpenAI(
        base_url=f"{llm_server_host}/v1",
        api_key="<empty>",  # required but unused
    )

    @app.post("/v1/chat/completions", response_model=None)
    async def chat_completions(request: Request) -> Any:
        try:
            try:
                body = await request.json()
            except json.JSONDecodeError as e:
                # Don't log the full traceback for expected errors
                forward_logger.info(
                    f"[dim]{request.state.request_id}[/] Invalid JSON received: {str(e)}"
                )
                raise HTTPException(
                    status_code=400,
                    detail={
                        "error": f"Invalid JSON: {str(e)}",
                        "request_id": request.state.request_id,
                    },
                )

            stream = body.get("stream", False)

            try:
                if stream:
                    forward_logger.debug(
                        f"[dim]{request.state.request_id}[/] Starting streaming chat completion"
                    )
                    stream = await client.chat.completions.create(**body)
                    return StreamingResponse(
                        stream_generator(stream), media_type="text/event-stream"
                    )
                else:
                    forward_logger.debug(
                        f"[dim]{request.state.request_id}[/] Starting chat completion"
                    )
                    response = await client.chat.completions.create(**body)
                    return JSONResponse(response.model_dump())
            except APITimeoutError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] LLM server timeout: {str(e)}"
                )
                raise HTTPException(
                    status_code=504,
                    detail={
                        "error": "LLM server took too long to respond",
                        "request_id": request.state.request_id,
                    },
                )
            except APIConnectionError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] Failed to connect to LLM server: {str(e)}"
                )
                raise HTTPException(
                    status_code=503,
                    detail={
                        "error": f"Failed to connect to LLM server. Is it running at {llm_server_host}?",
                        "request_id": request.state.request_id,
                    },
                )
            except APIError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] LLM server error: {str(e)}"
                )
                raise HTTPException(
                    status_code=e.status_code if hasattr(e, "status_code") else 500,
                    detail={
                        "error": f"LLM server error: {str(e)}",
                        "request_id": request.state.request_id,
                    },
                )

        except HTTPException:
            raise
        except Exception as e:
            # Only log full traceback for unexpected errors
            forward_logger.exception(
                f"[dim]{request.state.request_id}[/] Error in chat completions endpoint"
            )
            raise HTTPException(
                status_code=500,
                detail={"error": str(e), "request_id": request.state.request_id},
            )

    @app.get("/v1/models", response_model=None)
    async def models(request: Request) -> Dict[str, Any]:
        """List available Ollama models."""
        try:
            try:
                response = await client.models.list()
                return JSONResponse(response.model_dump())
            except APIConnectionError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] Failed to connect to LLM server: {str(e)}"
                )
                raise HTTPException(
                    status_code=503,
                    detail={
                        "error": f"Failed to connect to LLM server. Is it running at {llm_server_host}?",
                        "request_id": request.state.request_id,
                    },
                )
            except APITimeoutError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] LLM server timeout: {str(e)}"
                )
                raise HTTPException(
                    status_code=504,
                    detail={
                        "error": "LLM server took too long to respond",
                        "request_id": request.state.request_id,
                    },
                )
            except APIError as e:
                forward_logger.error(
                    f"[dim]{request.state.request_id}[/] LLM server error: {str(e)}"
                )
                raise HTTPException(
                    status_code=e.status_code if hasattr(e, "status_code") else 500,
                    detail={
                        "error": f"LLM server error: {str(e)}",
                        "request_id": request.state.request_id,
                    },
                )

        except HTTPException:
            raise
        except Exception as e:
            forward_logger.exception(
                f"[dim]{request.state.request_id}[/] Unexpected error in list models endpoint"
            )
            raise HTTPException(
                status_code=500,
                detail={"error": str(e), "request_id": request.state.request_id},
            )

    @app.get("/health", response_model=None)
    async def health() -> Dict[str, str]:
        """Health check endpoint."""
        return {"status": "ok"}

    return app
