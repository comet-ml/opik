from . import engine_loader
from . import validator


def construct_pii_validator() -> validator.PIIValidator:
    engine = engine_loader.load_engine()
    return validator.PIIValidator(engine)
