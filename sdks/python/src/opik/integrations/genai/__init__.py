from .opik_tracker import track_genai
from . import encoder_extension

__all__ = ["track_genai"]

encoder_extension.register()
