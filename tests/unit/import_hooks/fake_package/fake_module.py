# Fake module

from unittest.mock import Mock

FUNCTION_1_MOCK = Mock(return_value="function-1-return-value")
FUNCTION_2_MOCK = Mock(return_value="function-2-return-value")
FUNCTION_3_MOCK = Mock()

STATIC_METHOD_MOCK = Mock(return_value="static-method-return-value")


def function1(*args, **kwargs):
    return FUNCTION_1_MOCK(*args, **kwargs)


def function2(*args, **kwargs):
    return FUNCTION_2_MOCK(*args, **kwargs)

def function3(*args, **kwargs):
    FUNCTION_3_MOCK(*args, **kwargs)
    raise Exception("raising-function-exception-message")


class Klass:
    clsmethodmock = Mock()

    def __init__(self):
        self.mock = Mock()
        self.mock2 = Mock()

    def method(self, *args, **kwargs):
        return self.mock(*args, **kwargs)

    def method2(self, *args, **kwargs):
        return self.mock2(*args, **kwargs)

    @classmethod
    def clsmethod(cls, *args, **kwargs):
        print("Locals", locals())
        return cls.clsmethodmock(*args, **kwargs)

    @staticmethod
    def statikmethod(*args, **kwargs):
        return STATIC_METHOD_MOCK(*args, **kwargs)


class Child(Klass):
    def method(self, *args, **kwargs):
        return super(Child, self).method(*args, **kwargs)
