from typing import Any
from langchain.load import serializable
from opik import jsonable_encoder


def register() -> None:
    def encoder_extension(obj: serializable.Serializable) -> Any:
        return obj.to_json()

    jsonable_encoder.register_encoder_extension(
        obj_type=serializable.Serializable,
        encoder=encoder_extension,
    )
