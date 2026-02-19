class _UseManyGuard:
    def __init__(self):
        self.calls = []

    def use_many(self, validator):
        self.calls.append(("use_many", validator))
        return self


class _UseGuard:
    def __init__(self):
        self.calls = []

    def use(self, validator):
        self.calls.append(("use", validator))
        return self


def _attach_validator(guard, validator):
    if hasattr(guard, "use_many"):
        return guard.use_many(validator)
    return guard.use(validator)


def test_uses_use_many_when_available():
    guard = _UseManyGuard()
    validator = object()

    result = _attach_validator(guard, validator)

    assert result is guard
    assert guard.calls == [("use_many", validator)]


def test_uses_use_when_use_many_is_unavailable():
    guard = _UseGuard()
    validator = object()

    result = _attach_validator(guard, validator)

    assert result is guard
    assert guard.calls == [("use", validator)]
