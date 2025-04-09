"""
Type annotations for boto3.compat module.

Copyright 2024 Vlad Emelianov
"""

import os

SOCKET_ERROR: type[ConnectionError]

def filter_python_deprecation_warnings() -> None: ...

rename_file = os.rename
