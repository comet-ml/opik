from google.genai import types as genai_types

import base64
from opik import jsonable_encoder

if True:
    pass


def test_opik_encoder_is_extended_after_integration_import__image_bytes_detected_in_content__bytes_converted_to_base64_string():
    content = genai_types.Content(
        parts=[genai_types.Part.from_bytes(data=b"somebytes", mime_type="image/png")],
        role="user",
    )

    encoded_content = jsonable_encoder.encode(content)

    assert encoded_content["parts"][0]["inline_data"] == {
        "data": base64.b64encode(b"somebytes").decode("utf-8"),
        "mime_type": "image/png",
    }


def test_opik_encoder_is_extended_after_integration_import__video_bytes_detected_in_content__bytes_converted_to_base64_string():
    content = genai_types.Content(
        parts=[genai_types.Part.from_bytes(data=b"somebytes", mime_type="video/avi")],
        role="user",
    )

    encoded_content = jsonable_encoder.encode(content)

    assert encoded_content["parts"][0]["inline_data"] == {
        "data": base64.b64encode(b"somebytes").decode("utf-8"),
        "mime_type": "video/avi",
    }
