class OpikException(Exception):
    pass


class DatasetItemUpdateOperationRequiresItemId(OpikException):
    pass


class ContextExtractorNotSet(OpikException):
    pass


class ConfigurationError(OpikException):
    pass


class ScoreMethodMissingArguments(OpikException):
    pass
