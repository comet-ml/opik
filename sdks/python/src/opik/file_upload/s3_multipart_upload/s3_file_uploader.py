import dataclasses
import logging
import pathlib
from typing import List, Optional, IO

import httpx

from opik import s3_httpx_client
from . import file_parts_strategy, s3_upload_error
from .. import file_upload_monitor


LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class PartMetadata:
    e_tag: str
    part_number: int
    size: int


class S3FileDataUploader:
    """Represents S3 file data uploader to be used for sending file data to AWS S3 bucket."""

    def __init__(
        self,
        file_parts: file_parts_strategy.FilePartsStrategy,
        pre_sign_urls: List[str],
        httpx_client: httpx.Client,
        monitor: Optional[file_upload_monitor.FileUploadMonitor] = None,
    ) -> None:
        self._file_parts = file_parts
        self._pre_sign_urls = pre_sign_urls
        self._httpx_client = httpx_client
        self._monitor = monitor

        self.uploaded_parts: List[PartMetadata] = []
        self.bytes_sent = 0

    def upload(self) -> List[PartMetadata]:
        self._file_parts.calculate()
        file_to_upload = pathlib.Path(self._file_parts.file)
        try:
            with file_to_upload.open("rb") as fp:
                self._upload(fp=fp)
        except Exception as e:
            raise s3_upload_error.S3UploadFileError(
                file=self._file_parts.file, reason=str(e)
            ) from e

        return self.uploaded_parts

    def _upload(self, fp: IO) -> None:
        max_file_part_size = self._file_parts.max_file_part_size
        for i, url in enumerate(self._pre_sign_urls):
            part_number = i + 1
            file_data = fp.read(max_file_part_size)
            self._send_data_part(url=url, file_data=file_data, part_number=part_number)

    @s3_httpx_client.s3_retry
    def _send_data_part(self, url: str, file_data: bytes, part_number: int) -> None:
        response = self._httpx_client.put(
            url=url,
            content=file_data,
        )
        if response.status_code == httpx.codes.OK:
            e_tag = response.headers["ETag"]
            self._on_part_complete(
                PartMetadata(e_tag=e_tag, part_number=part_number, size=len(file_data))
            )

        response.raise_for_status()

        LOGGER.debug(
            "Part [%04d / %04d] of file '%s' was uploaded to S3",
            part_number,
            len(self._pre_sign_urls),
            self._file_parts.file,
        )

    def _on_part_complete(self, part: PartMetadata) -> None:
        self.uploaded_parts.append(part)
        self.bytes_sent += part.size
        if self._monitor is not None:
            self._monitor.update(part.size)
