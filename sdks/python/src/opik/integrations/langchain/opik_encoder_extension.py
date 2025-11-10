from typing import Any, Dict
from langchain_core.load import serializable
import opik.jsonable_encoder as jsonable_encoder


def register() -> None:
    def encoder_extension(obj: serializable.Serializable) -> Dict[str, Any]:
        return obj.model_dump()

    jsonable_encoder.register_encoder_extension(
        obj_type=serializable.Serializable,
        encoder=encoder_extension,
    )
