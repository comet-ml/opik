from unittest import mock

import pytest

from comet_llm.import_hooks import finder, registry


@pytest.fixture
def fake_module_path():
    parent_name = '.'.join(__name__.split('.')[:-1])

    return f"{parent_name}.fake_package.fake_module"

@pytest.mark.forked
def test_patch_functions_in_module__register_after__without_arguments(fake_module_path):
    extensions_registry = registry.Registry()

    # Prepare
    mock1 = mock.Mock()
    extensions_registry.register_after(fake_module_path, "function1", mock1)

    mock2 = mock.Mock()
    extensions_registry.register_after(fake_module_path, "function2", mock2)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.function1()
    fake_module.function2()

    # Check function1
    mock1.assert_called_once_with(mock.ANY, "function-1-return-value")
    original = mock1.call_args[0][0]
    assert original.__name__ == "function1"

    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with()

    # Check function2
    mock2.assert_called_once_with(mock.ANY, "function-2-return-value")
    original = mock2.call_args[0][0]
    assert original.__name__ == "function2"

    assert original is not fake_module.function2

    fake_module.FUNCTION_2_MOCK.assert_called_once_with()


@pytest.mark.forked
def test_patch_functions_in_module__register_before__without_arguments(fake_module_path):
    extensions_registry = registry.Registry()

    # Prepare
    mock1 = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function1", mock1)

    mock2 = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function2", mock2)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.function1()
    fake_module.function2()

    # Check function1
    mock1.assert_called_once_with(mock.ANY)
    original = mock1.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with()

    # Check function2
    mock1.assert_called_once_with(mock.ANY)
    original = mock2.call_args[0][0]
    assert original.__name__ == "function2"
    assert original is not fake_module.function2

    fake_module.FUNCTION_2_MOCK.assert_called_once_with()
