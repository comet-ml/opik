class S3UploadError(Exception):
    def __init__(self, reason: str, connection_error: bool):
        self.reason = reason
        self.connection_error = connection_error

    def __str__(self) -> str:
        return f"S3 file upload failed, reason: {self.reason}, connection error: {self.connection_error}"


class S3UploadFileError(S3UploadError):
    def __init__(self, file: str, reason: str, connection_error: bool = False):
        super().__init__(reason, connection_error=connection_error)
        self.file = file

    def __str__(self) -> str:
        return f"S3 upload failed for file: '{self.file}'. Reason: {self.reason}"


class S3UploadErrorFileIsTooLarge(S3UploadFileError):
    def __init__(self, file: str, reason: str):
        super().__init__(file, reason)


class S3UploadErrorFileIsEmpty(S3UploadFileError):
    def __init__(self, file: str, reason: str):
        super().__init__(file, reason)
