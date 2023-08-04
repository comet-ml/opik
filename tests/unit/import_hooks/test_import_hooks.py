from unittest import mock

import pytest

from comet_llm.import_hooks import finder, registry


@pytest.fixture
def fake_module_path():
    parent_name = '.'.join(__name__.split('.')[:-1])

    return f"{parent_name}.fake_package.fake_module"

ORIGINAL = mock.ANY
EXCEPTION = mock.ANY


@pytest.mark.forked
def test_patch_function_in_module__name_to_patch_not_found__no_failure(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    extensions_registry.register_after(fake_module_path, "non_existing_function", "any-callback")

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    assert hasattr(fake_module, "non_existing_function") is False

@pytest.mark.forked
def test_patch_functions_in_module__register_after__without_arguments(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    
    mock_callback1 = mock.Mock()
    extensions_registry.register_after(fake_module_path, "function1", mock_callback1)

    mock_callback2 = mock.Mock()
    extensions_registry.register_after(fake_module_path, "function2", mock_callback2)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    fake_module.FUNCTION_2_MOCK.return_value = "function-2-return-value"
    fake_module.function1()
    fake_module.function2()

    # Check function1
    mock_callback1.assert_called_once_with(ORIGINAL, "function-1-return-value")
    original = mock_callback1.call_args[0][0]
    assert original.__name__ == "function1"

    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with()

    # Check function2
    mock_callback2.assert_called_once_with(ORIGINAL, "function-2-return-value")
    original = mock_callback2.call_args[0][0]
    assert original.__name__ == "function2"
    assert original is not fake_module.function2

    fake_module.FUNCTION_2_MOCK.assert_called_once_with()


@pytest.mark.forked
def test_patch_functions_in_module__register_before__without_arguments(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()

    mock_callback1 = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function1", mock_callback1)

    mock_callback2 = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function2", mock_callback2)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    fake_module.FUNCTION_2_MOCK.return_value = "function-2-return-value"
    assert fake_module.function1() == "function-1-return-value"
    assert fake_module.function2() == "function-2-return-value"

    # Check function1
    mock_callback1.assert_called_once_with(ORIGINAL)
    original = mock_callback1.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with()

    # Check function2
    mock_callback1.assert_called_once_with(ORIGINAL)
    original = mock_callback2.call_args[0][0]
    assert original.__name__ == "function2"
    assert original is not fake_module.function2

    fake_module.FUNCTION_2_MOCK.assert_called_once_with()


@pytest.mark.forked
def test_patch_functions_in_module__register_before_and_after__without_arguments(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()

    mock_callback1 = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function1", mock_callback1)

    mock_callback2 = mock.Mock(return_value=None)
    extensions_registry.register_after(fake_module_path, "function2", mock_callback2)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    fake_module.FUNCTION_2_MOCK.return_value = "function-2-return-value"
    assert fake_module.function1() == "function-1-return-value"
    assert fake_module.function2() == "function-2-return-value"

    # Check function1
    mock_callback1.assert_called_once_with(ORIGINAL)
    original = mock_callback1.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with()

    # Check function2
    mock_callback2.assert_called_once_with(ORIGINAL, "function-2-return-value")
    original = mock_callback2.call_args[0][0]
    assert original.__name__ == "function2"
    assert original is not fake_module.function2

    fake_module.FUNCTION_2_MOCK.assert_called_once_with()


@pytest.mark.forked
def test_patch_function_in_module__register_before__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock()
    extensions_registry.register_before(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "function-1-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_function_in_module__register_before__callback_changes_input_arguments(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(
        return_value=(
            ("new-arg-1", "new-arg-2"),
            {"kwarg1":"new-kwarg-1", "kwarg2":"new-kwarg-2"}
        )
    )
    extensions_registry.register_before(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "function-1-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("new-arg-1", "new-arg-2", kwarg1="new-kwarg-1", kwarg2="new-kwarg-2")


@pytest.mark.forked
def test_patch_function_in_module__register_before__error_in_callback__original_function_worked(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(side_effect=Exception)
    extensions_registry.register_before(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "function-1-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")



@pytest.mark.forked
def test_patch_function_in_module__register_after__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(return_value=None)
    extensions_registry.register_after(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "function-1-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "function-1-return-value", "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_function_in_module__register_after__error_in_callback__original_function_worked(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(side_effect=Exception)
    extensions_registry.register_after(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "function-1-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "function-1-return-value", "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_function_in_module__register_after__callback_changes_return_value(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(return_value="new-return-value")
    extensions_registry.register_after(fake_module_path, "function1", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    fake_module.FUNCTION_1_MOCK.return_value = "function-1-return-value"
    assert fake_module.function1("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2") == "new-return-value"

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, "function-1-return-value", "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function1"
    assert original is not fake_module.function1

    fake_module.FUNCTION_1_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_raising_function_in_module__register_after_exception__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock()
    extensions_registry.register_after_exception(fake_module_path, "function3", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    with pytest.raises(Exception):
        fake_module.function3("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")

    # Check
    ORIGINAL = mock.ANY
    EXCEPTION = mock.ANY
    mock_callback.assert_called_once_with(ORIGINAL, EXCEPTION, "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function3"
    assert original is not fake_module.function3

    fake_module.FUNCTION_3_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_raising_function_in_module__register_after_exception__error_in_callback__original_function_worked(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock(side_effect=Exception)
    extensions_registry.register_after_exception(fake_module_path, "function3", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    with pytest.raises(Exception):
        fake_module.function3("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")

    # Check
    mock_callback.assert_called_once_with(ORIGINAL, EXCEPTION, "arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "function3"
    assert original is not fake_module.function3

    fake_module.FUNCTION_3_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1", kwarg2="kwarg-2")


@pytest.mark.forked
def test_patch_method_in_module__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock()
    extensions_registry.register_before(fake_module_path, "Klass.method", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Call
    instance = fake_module.Klass()

    # Set the return
    instance.mock.return_value = mock.sentinel.METHOD

    # Call
    assert instance.method("arg-1", "arg-2", kwarg1="kwarg-1") == mock.sentinel.METHOD

    # Check method
    mock_callback.assert_called_once_with(ORIGINAL, instance, "arg-1", "arg-2", kwarg1="kwarg-1")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "method"
    assert original is not fake_module.Klass.method

    instance.mock.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1")


@pytest.mark.forked
def test_patch_class_method_in_module__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock()
    extensions_registry.register_before(fake_module_path, "Klass.clsmethod", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Set the return
    fake_module.Klass.clsmethodmock.return_value = mock.sentinel.METHOD

    # Call
    assert fake_module.Klass.clsmethod("arg-1", "arg-2", kwarg1="kwarg-1") == mock.sentinel.METHOD

    # Check method
    mock_callback.assert_called_once_with(ORIGINAL, fake_module.Klass, "arg-1", "arg-2", kwarg1="kwarg-1")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "clsmethod"

    assert original is not fake_module.Klass.clsmethod

    fake_module.Klass.clsmethodmock.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1")


@pytest.mark.forked
def test_patch_static_method_in_module__happyflow(fake_module_path):
    # Prepare
    extensions_registry = registry.Registry()
    mock_callback = mock.Mock()
    extensions_registry.register_before(fake_module_path, "Klass.statikmethod", mock_callback)

    comet_finder = finder.CometFinder(extensions_registry)
    comet_finder.hook_into_import_system()

    # Import
    from .fake_package import fake_module

    # Set the return
    fake_module.STATIC_METHOD_MOCK.return_value = mock.sentinel.METHOD

    # Call
    assert fake_module.Klass.statikmethod("arg-1", "arg-2", kwarg1="kwarg-1") == mock.sentinel.METHOD

    # Check method
    mock_callback.assert_called_once_with(ORIGINAL, "arg-1", "arg-2", kwarg1="kwarg-1")
    original = mock_callback.call_args[0][0]
    assert original.__name__ == "statikmethod"
    assert original is not fake_module.Klass.statikmethod

    fake_module.STATIC_METHOD_MOCK.assert_called_once_with("arg-1", "arg-2", kwarg1="kwarg-1")