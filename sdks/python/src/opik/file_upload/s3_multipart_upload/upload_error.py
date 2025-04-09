from typing import Optional


class S3UploadError(Exception):
    def __init__(self, reason: str, due_connection_error: bool = False):
        self.reason = reason
        self.due_connection_error = due_connection_error

    def __str__(self) -> str:
        msg = "S3 file upload failed, reason:%s, due connection error: %s"
        return msg % (self.reason, self.due_connection_error)


class S3UploadFileError(S3UploadError):
    def __init__(
        self,
        file: str,
        reason: str,
        retry_attempts: Optional[int] = None,
        due_connection_error: bool = False,
    ):
        super().__init__(reason, due_connection_error)
        self.file = file
        self.retry_attempts = retry_attempts

    def __str__(self) -> str:
        msg = "S3 file upload failed, file: %r. Reason: %r, due connection error: %s"
        return msg % (self.file, self.reason, self.due_connection_error)


class S3UploadErrorFileIsTooLarge(S3UploadFileError):
    def __init__(self, file: str, reason: str):
        super().__init__(file, reason)


class S3UploadErrorFileIsEmpty(S3UploadFileError):
    def __init__(self, file: str, reason: str):
        super().__init__(file, reason)
