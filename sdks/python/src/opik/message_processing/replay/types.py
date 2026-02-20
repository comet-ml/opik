from typing import Callable

from .. import messages

ReplayCallback = Callable[[messages.BaseMessage], None]
