from typing import Any

import pydantic


class TaskOutput(pydantic.BaseModel):
    # Keys that are already used by our metrics.
    input: Any = None
    output: Any = None
    expected: Any = None
    context: Any = None
    metadata: Any = None

    # Model config allows to provide custom fields.
    # It might be especially relevant for custom metrics.
    model_config = pydantic.ConfigDict(extra="allow", strict=False)
