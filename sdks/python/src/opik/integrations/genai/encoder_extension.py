import base64
from typing import Any, Dict, Union

from google.genai import types as genai_types
import opik.jsonable_encoder as jsonable_encoder


def register() -> None:
    def encoder_extension(obj: genai_types.Blob) -> Union[str, Dict[str, Any]]:
        if obj.mime_type is not None and obj.data is not None:
            return {
                "data": base64.b64encode(obj.data).decode("utf-8"),
                "mime_type": obj.mime_type,
            }

        return obj.model_dump()

    jsonable_encoder.register_encoder_extension(
        obj_type=genai_types.Blob,
        encoder=encoder_extension,
    )
