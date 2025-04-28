import dataclasses
from typing import Optional


@dataclasses.dataclass
class FileUploadMonitor:
    total_size: Optional[int] = None
    bytes_sent: int = 0

    def update(self, bytes_sent: int) -> None:
        self.bytes_sent += bytes_sent

    def reset(self) -> None:
        self.bytes_sent = 0
