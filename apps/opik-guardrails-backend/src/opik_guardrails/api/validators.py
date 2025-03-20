from typing import List, Optional

import pydantic


class TopicClassificationRequest(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(from_attributes=True)

    text: str = pydantic.Field(description="The text to classify")
    topics: List[str] = pydantic.Field(
        min_items=1, description="A list of topics to check"
    )
    threshold: Optional[float] = pydantic.Field(
        None, description="A threshold value for classifier"
    )


class PIIDetectionRequest(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(from_attributes=True)

    text: str = pydantic.Field(description="The text to analyze for PII")
    entities: Optional[List[str]] = pydantic.Field(
        None, description="Optional list of entity types to detect"
    )
    language: Optional[str] = pydantic.Field(
        None, description="Language of the text (default: 'en')"
    )
