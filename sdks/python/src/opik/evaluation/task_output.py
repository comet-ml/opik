import pydantic


class TaskOutput(pydantic.BaseModel):
    # Keys that are already used by our metrics.
    #
    # input
    # output
    # expected
    # context
    # metadata

    # Model config allows to provide custom fields.
    # It might be especially relevant for custom metrics.
    model_config = pydantic.ConfigDict(extra="allow", strict=False)
