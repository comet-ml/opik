"""Logging utilities for LLM framework integrations.

Provides a clean, standardized logging interface with Rich formatting for various LLM applications.

Example:
    from opik_optimizer.utils.llm_logger import LLMLogger

    logger = LLMLogger("microsoft_agent_framework", suppress=["agent_framework"])
    logger.agent_init(model="gpt-4", tools=["search_wikipedia"])
    logger.agent_invoke(query="What is the capital of France?")
    logger.tool_call("search_wikipedia", query="France capital")
    logger.tool_result(result_count=3)
    logger.agent_response(response="Paris is the capital of France.")
"""

from __future__ import annotations

import logging
from typing import Any

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
            self.agent_name = " ".join(word.capitalize() for word in framework_name.split("_"))

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

    def tool_call(self, tool_name: str, query: str | None = None, max_length: int = 50) -> None:
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

    def tool_result(
        self,
        tool_name: str | None = None,
        result_count: int | None = None,
        success: bool = True,
    ) -> None:
        """Log tool result (INFO level).

        Args:
            tool_name: Optional tool name
            result_count: Optional count of results
            success: Whether the tool call succeeded
        """
        if success:
            msg = "[yellow]✓[/yellow]"
            if tool_name:
                msg += f" {tool_name}:"
            if result_count is not None:
                msg += f" Retrieved {result_count} results"
            self._logger.info(msg)
        else:
            msg = "[yellow]✗[/yellow]"
            if tool_name:
                msg += f" {tool_name} failed"
            self._logger.warning(msg)

    def agent_response(self, response: str, max_length: int = 80) -> None:
        """Log agent response (INFO level).

        Args:
            response: Response from the agent
            max_length: Maximum length to display (truncate with ...)
        """
        truncated = response[:max_length] + "..." if len(response) > max_length else response
        self._logger.info(
            f"[cyan]←[/cyan] {self.agent_name} response: [green]{truncated}[/green]"
        )

    def agent_error(self, error: Exception | str, include_traceback: bool = False) -> None:
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

    def backend_selection(self, backend: str, query: str, max_length: int = 60) -> None:
        """Log backend selection for a query (INFO level).

        Args:
            backend: Name of the backend (e.g., "Wikipedia API", "ColBERTv2")
            query: Query being processed
            max_length: Maximum length to display (truncate with ...)
        """
        truncated = query[:max_length] + "..." if len(query) > max_length else query
        self._logger.info(f"{backend}: [italic]{truncated}[/italic]")

    def backend_fallback(self, failed_backend: str, fallback_backend: str) -> None:
        """Log backend fallback (INFO level).

        Args:
            failed_backend: Backend that failed
            fallback_backend: Backend being used as fallback
        """
        self._logger.info(f"{failed_backend} failed, fallback to {fallback_backend}")

    def backend_error(self, backend: str, query: str, error: Exception | str) -> None:
        """Log backend error (WARNING level).

        Args:
            backend: Name of the backend
            query: Query that failed
            error: Error message or exception
        """
        self._logger.warning(f"{backend} failed for '{query}': {error}")

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
