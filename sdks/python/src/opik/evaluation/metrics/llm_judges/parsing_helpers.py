from typing import Dict
import json
from ....exceptions import JSONParsingError


def convert_to_json(content: str) -> Dict:
    try:
        return json.loads(content)
    except json.decoder.JSONDecodeError:
        try:
            import instructor
        except ImportError:
            raise JSONParsingError(
                "Failed to parse response to JSON, install the `instructor` library using `pip install instructor` for Opik to use more robust parsing strategies."
            )

        try:
            json_string = instructor.utils.extract_json_from_codeblock(content)
            return json.loads(json_string)
        except Exception as e:
            raise JSONParsingError(f"Failed to parse response to JSON: {str(e)}")
    except Exception as e:
        raise JSONParsingError(f"Failed to parse response to JSON: {str(e)}")
