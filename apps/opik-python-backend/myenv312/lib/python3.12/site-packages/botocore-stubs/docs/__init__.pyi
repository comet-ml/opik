"""
Type annotations for botocore.docs module.

Copyright 2025 Vlad Emelianov
"""

from botocore.session import Session

DEPRECATED_SERVICE_NAMES: set[str] = ...

def generate_docs(root_dir: str, session: Session) -> None: ...
