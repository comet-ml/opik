import base64
import functools
from typing import Any, Dict, Union

from google.genai import types as genai_types
from opik import jsonable_encoder


@functools.lru_cache
def register() -> None:
    def encoder_extension(obj: genai_types.Blob) -> Union[str, Dict[str, Any]]:
        if (
            obj.mime_type is not None
            and obj.data is not None
            and obj.mime_type.startswith("image")
        ):
            return base64.b64encode(obj.data).decode("utf-8")

        return obj.model_dump()

    jsonable_encoder.register_encoder_extension(
        obj_type=genai_types.Blob,
        encoder=encoder_extension,
    )
