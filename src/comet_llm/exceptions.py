class CometLLMException(Exception):
    pass


class CometLLMRestApiException(CometLLMException):
    pass


class CometAPIKeyIsMissing(CometLLMException):
    pass