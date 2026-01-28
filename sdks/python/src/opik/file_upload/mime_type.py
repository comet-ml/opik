import mimetypes


def guess_mime_type(file: str | None, strict: bool = True) -> str | None:
    if file is not None:
        return mimetypes.guess_type(file, strict=strict)[0]

    return None
