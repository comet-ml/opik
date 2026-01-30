"""
Type definitions for Agent Trace spec v1.0.

Agent Trace Spec: https://github.com/cursor/agent-trace
"""

from typing import Any, Dict, List, Optional, TypedDict


class AgentTraceContributor(TypedDict, total=False):
    """Contributor information."""

    type: str  # "human" | "ai" | "mixed" | "unknown"
    model_id: Optional[str]


class AgentTraceRange(TypedDict, total=False):
    """Line range information."""

    start_line: int
    end_line: int
    content_hash: Optional[str]
    contributor: Optional[AgentTraceContributor]


class AgentTraceConversation(TypedDict, total=False):
    """Conversation information."""

    url: Optional[str]
    contributor: Optional[AgentTraceContributor]
    ranges: List[AgentTraceRange]
    related: Optional[List[Dict[str, str]]]


class AgentTraceFileEntry(TypedDict, total=False):
    """File entry."""

    path: str
    conversations: List[AgentTraceConversation]


class AgentTraceVcs(TypedDict, total=False):
    """VCS information."""

    type: str  # "git" | "jj" | "hg" | "svn"
    revision: str


class AgentTraceTool(TypedDict, total=False):
    """Tool information."""

    name: str
    version: Optional[str]


class AgentTraceRecord(TypedDict, total=False):
    """Agent Trace record (v1.0 spec)."""

    version: str
    id: str
    timestamp: str
    vcs: Optional[AgentTraceVcs]
    tool: Optional[AgentTraceTool]
    files: List[AgentTraceFileEntry]
    metadata: Optional[Dict[str, Any]]
