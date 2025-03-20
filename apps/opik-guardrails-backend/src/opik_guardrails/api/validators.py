import pydantic 
from typing import List, Optional


class TopicClassificationRequest(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(from_attributes=True)

    text: str = pydantic.Field(description="The text to classify")
    topics: List[str] = pydantic.Field(min_items=1, description="A list of topics to check")
    threshold: Optional[float] = pydantic.Field(None, description="A threshold value for classifier")