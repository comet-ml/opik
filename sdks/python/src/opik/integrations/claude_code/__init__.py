"""
Opik integration for Claude Code Python SDK.

This module provides integration with Anthropic's Claude Code SDK,
allowing automatic tracking of Claude Code API calls as Opik spans and traces.
"""

from .opik_tracker import track_claude_code, track_claude_code_with_options

__all__ = ["track_claude_code", "track_claude_code_with_options"]
