from typing import Any
import json
import opik.exceptions as exceptions


def extract_json_content_or_raise(content: str) -> Any:
    try:
        return json.loads(content)
    except json.decoder.JSONDecodeError:
        return _extract_presumably_json_dict_or_raise(content)
    except Exception as e:
        raise exceptions.JSONParsingError(
            f"Failed to parse response to JSON dictionary: {str(e)}"
        )


def _extract_presumably_json_dict_or_raise(content: str) -> str:
    try:
        first_paren = content.find("{")
        last_paren = content.rfind("}")
        json_string = content[first_paren : last_paren + 1]
        return json.loads(json_string)
    except Exception as e:
        raise exceptions.JSONParsingError(
            f"Failed to extract presumably JSON dictionary: {str(e)}"
        )
