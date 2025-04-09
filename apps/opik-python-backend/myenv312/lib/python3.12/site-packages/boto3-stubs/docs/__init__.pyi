"""
Type annotations for boto3.docs module.

Copyright 2024 Vlad Emelianov
"""

from boto3.session import Session

def generate_docs(root_dir: str, session: Session) -> None: ...
