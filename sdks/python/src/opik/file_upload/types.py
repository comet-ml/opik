from typing import Callable

OnUploadSuccessCallback = Callable[[], None]
OnUploadFailureCallback = Callable[[Exception], None]
