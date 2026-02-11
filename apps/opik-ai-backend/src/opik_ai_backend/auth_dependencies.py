"""Authentication dependencies for OpikAssist.

This module extracts session tokens and workspace headers from requests
and passes them through to the Opik backend, which handles authentication itself.
"""

import hashlib
from typing import Optional

from fastapi import Request
from pydantic import BaseModel


class UserContext(BaseModel):
    """User context extracted from request cookies and headers."""

    session_token: Optional[str]
    workspace_name: Optional[str]

    @property
    def user_id(self) -> str:
        """
        Derive a deterministic user_id for ADK session isolation.

        If session_token exists, use a hash of it.
        Otherwise, use "default" for standalone mode.
        """
        if self.session_token:
            return hashlib.sha256(self.session_token.encode()).hexdigest()[:16]
        return "default"


async def get_current_user(request: Request) -> UserContext:
    """
    Extract user context from request.

    Args:
        request: FastAPI request object

    Returns:
        UserContext with session_token and workspace_name
    """
    session_token = request.cookies.get("sessionToken")
    workspace_name = request.headers.get("comet-workspace")

    return UserContext(session_token=session_token, workspace_name=workspace_name)
