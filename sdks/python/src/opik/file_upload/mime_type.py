import mimetypes
from typing import Optional

BINARY_MIME_TYPE = "application/octet-stream"


def guess_mime_type(file: Optional[str], strict: bool = True) -> Optional[str]:
    if file is not None:
        return mimetypes.guess_type(file, strict=strict)[0]

    return None
