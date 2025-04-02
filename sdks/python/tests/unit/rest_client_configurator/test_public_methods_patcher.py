from unittest import mock
from opik.rest_client_configurator import public_methods_patcher


class Parent:
    def parent_f(self):
        pass


class Child(Parent):
    def child_f(self):
        pass

    def _private_child_f(self):
        pass


def test_public_methods_patcher():
    decorator_mock = mock.Mock()

    def wrapper(func):
        def wrapped(*args, **kwargs):
            decorator_mock()
            return func(*args, **kwargs)

        return wrapped

    child_instance = Child()

    public_methods_patcher.patch(child_instance, wrapper)
    child_instance.child_f()
    decorator_mock.assert_called_once()
    decorator_mock.reset_mock()

    child_instance._private_child_f()
    decorator_mock.assert_not_called()

    child_instance.parent_f()
    decorator_mock.assert_called_once()
