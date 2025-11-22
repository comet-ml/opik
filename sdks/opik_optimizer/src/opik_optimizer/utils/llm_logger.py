"""Logging utilities for LLM framework integrations.

Provides a clean, standardized logging interface with Rich formatting for various LLM applications.

Example:
    from opik_optimizer.utils.llm_logger import LLMLogger

    logger = LLMLogger("microsoft_agent_framework", suppress=["agent_framework"])
    logger.agent_init(model="gpt-4", tools=["search_wikipedia"])

    # Use context manager for cleaner lifecycle logging
    with logger.log_invoke(question):
        result = agent.run(question)
        return result  # Auto-logs response and errors
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from typing import Any
from collections.abc import Generator

__all__ = ["LLMLogger"]


class LLMLogger:
    """Clean logging interface for LLM framework integrations.

    Automatically handles logger setup, Rich formatting, and framework log suppression.
    """

    def __init__(
        self,
        framework_name: str,
        agent_name: str | None = None,
        log_level: int = logging.INFO,
        suppress: list[str] | None = None,
    ) -> None:
        """Initialize LLM logger.

        Args:
            framework_name: Name of the framework (e.g., "microsoft_agent_framework")
            agent_name: Optional agent display name (defaults to formatted framework_name)
            log_level: Logging level (default: INFO)
            suppress: List of logger names to suppress (set to WARNING)
        """
        self._logger = logging.getLogger(f"opik_optimizer.{framework_name}")
        self._logger.setLevel(log_level)

        # Set agent display name
        if agent_name:
            self.agent_name = agent_name
        else:
            # Auto-format: "microsoft_agent_framework" -> "Microsoft Agent Framework"
            self.agent_name = " ".join(
                word.capitalize() for word in framework_name.split("_")
            )

        # Suppress noisy framework logs
        if suppress:
            for framework_logger in suppress:
                logging.getLogger(framework_logger).setLevel(logging.WARNING)

    def agent_init(self, model: str, tools: list[str] | None = None) -> None:
        """Log agent initialization (INFO level).

        Args:
            model: Model identifier
            tools: Optional list of tool names
        """
        tools_str = f", tools=[yellow]{', '.join(tools)}[/yellow]" if tools else ""
        self._logger.info(
            f"[cyan]{self.agent_name}:[/cyan] model=[bold]{model}[/bold]{tools_str}"
        )

    def agent_invoke(self, query: str, max_length: int = 60) -> None:
        """Log agent invocation (INFO level).

        Args:
            query: Query being processed
            max_length: Maximum length to display (truncate with ...)
        """
        truncated = query[:max_length] + "..." if len(query) > max_length else query
        self._logger.info(
            f"[cyan]→[/cyan] {self.agent_name} processing: [italic]{truncated}[/italic]"
        )

    def tool_call(
        self, tool_name: str, query: str | None = None, max_length: int = 50
    ) -> None:
        """Log tool call (INFO level).

        Args:
            tool_name: Name of the tool being called
            query: Optional query/argument being passed to tool
            max_length: Maximum length to display (truncate with ...)
        """
        if query:
            truncated = query[:max_length] + "..." if len(query) > max_length else query
            self._logger.info(
                f"[yellow]🔧 Tool:[/yellow] {tool_name}([italic]{truncated}[/italic])"
            )
        else:
            self._logger.info(f"[yellow]🔧 Tool:[/yellow] {tool_name}")

    def agent_response(self, response: str, max_length: int = 80) -> None:
        """Log agent response (INFO level).

        Args:
            response: Response from the agent
            max_length: Maximum length to display (truncate with ...)
        """
        truncated = (
            response[:max_length] + "..." if len(response) > max_length else response
        )
        self._logger.info(
            f"[cyan]←[/cyan] {self.agent_name} response: [green]{truncated}[/green]"
        )

    def agent_error(
        self, error: Exception | str, include_traceback: bool = False
    ) -> None:
        """Log agent error (ERROR level).

        Args:
            error: Error message or exception
            include_traceback: Whether to include full traceback
        """
        error_msg = str(error)
        self._logger.error(
            f"[bold red]✗ {self.agent_name} failed:[/bold red] {error_msg}",
            exc_info=include_traceback,
        )

    def debug(self, message: str, **kwargs: Any) -> None:
        """Log internal implementation details (DEBUG level).

        Args:
            message: Debug message
            **kwargs: Additional context to include
        """
        if kwargs:
            self._logger.debug(f"{message} | {kwargs}")
        else:
            self._logger.debug(message)

    def info(self, message: str) -> None:
        """Log informational message (INFO level).

        Args:
            message: Info message (supports Rich markup)
        """
        self._logger.info(message)

    @contextmanager
    def log_invoke(self, input_text: str) -> Generator[dict[str, Any]]:
        """Context manager for agent invoke lifecycle logging.

        Automatically logs:
        - Entry: agent_invoke(input_text)
        - Exit (success): agent_response(result)
        - Exit (error): agent_error(exception)

        Usage:
            with logger.log_invoke(question) as ctx:
                result = agent.run(question)
                ctx["response"] = result  # Optional: manually set response
                return result

        Or simpler (response logged automatically if set):
            with logger.log_invoke(question):
                return agent.run(question)  # Must store in variable first

        Args:
            input_text: The input/question being processed

        Yields:
            Context dict where you can optionally set "response" key
        """
        self.agent_invoke(input_text)
        context = {"response": None}
        try:
            yield context
            # Log response if it was set
            if context["response"] is not None:
                self.agent_response(context["response"])
        except Exception as exc:
            self.agent_error(exc, include_traceback=True)
            raise

    @contextmanager
    def log_tool(self, tool_name: str, query: str | None = None) -> Generator[None]:
        """Context manager for tool call lifecycle logging.

        Automatically logs:
        - Entry: tool_call(tool_name, query)
        - Exit (error): agent_error(exception)

        Usage:
            with logger.log_tool("search_wikipedia", query):
                result = search_wikipedia(query)
                return result

        Args:
            tool_name: Name of the tool being called
            query: Optional query/argument
        """
        self.tool_call(tool_name, query)
        try:
            yield
        except Exception as exc:
            self.agent_error(exc, include_traceback=True)
            raise
