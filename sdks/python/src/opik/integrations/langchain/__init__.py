from .opik_tracer import OpikTracer
from . import opik_encoder_extension

opik_encoder_extension.register()

__all__ = ["OpikTracer"]
