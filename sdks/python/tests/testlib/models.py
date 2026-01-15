import dataclasses
import datetime
from typing import Optional, List

from opik.message_processing.emulation import models
from .any_compare_helpers import ANY, ANY_BUT_NONE


@dataclasses.dataclass
class SpanModel(models.SpanModel):
    project_name: str = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check the project name unless it's specified explicitly in the test
    last_updated_at: Optional[datetime.datetime] = dataclasses.field(
        default_factory=lambda: ANY_BUT_NONE
    )  # we don't want to check the last_updated_at unless it's specified explicitly in the test - just make sure it's not None
    attachments: Optional[List[models.AttachmentModel]] = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check attachments unless explicitly specified in the test


@dataclasses.dataclass
class TraceModel(models.TraceModel):
    project_name: str = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check the project name unless it's specified explicitly in the test
    attachments: Optional[List[models.AttachmentModel]] = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check attachments unless explicitly specified in the test


@dataclasses.dataclass
class FeedbackScoreModel(models.FeedbackScoreModel):
    pass


@dataclasses.dataclass
class AttachmentModel(models.AttachmentModel):
    pass
