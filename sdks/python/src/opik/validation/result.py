class ValidationResult:
    def __init__(self, failed: bool, failure_reasons: list[str] | None = None):
        self._failed = failed
        self._failure_reasons: list[str] = (
            failure_reasons if failure_reasons is not None else []
        )

    def failed(self) -> bool:
        return self._failed

    def ok(self) -> bool:
        return not self._failed

    @property
    def failure_reasons(self) -> list[str]:
        return self._failure_reasons

    def __bool__(self) -> bool:
        return not self._failed

    def __str__(self) -> str:
        if not self._failed:
            return "OK"

        return f"Failed: {self._failure_reasons}"
