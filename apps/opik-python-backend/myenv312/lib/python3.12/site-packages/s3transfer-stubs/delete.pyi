"""
Type annotations for s3transfer.delete module.

Copyright 2025 Vlad Emelianov
"""

from s3transfer.tasks import SubmissionTask as SubmissionTask
from s3transfer.tasks import Task as Task

class DeleteSubmissionTask(SubmissionTask): ...
class DeleteObjectTask(Task): ...
