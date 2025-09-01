import functools

from opik.decorator.inspect_helpers import get_function_name


class TestGetFunctionName:
    """Test cases for the get_function_name function"""

    def test_regular_function(self):
        """Test that get_function_name works correctly for regular functions"""

        def regular_function():
            return "hello"

        assert get_function_name(regular_function) == "regular_function"

    def test_partial_function(self):
        """Test that get_function_name works correctly for functools.partial objects"""

        def function_with_args(x, y):
            return x + y

        # Create a partial function
        partial_function = functools.partial(function_with_args, x=10)

        # Should return the name of the underlying function
        assert get_function_name(partial_function) == "function_with_args"

    def test_partial_function_with_nested_partial(self):
        """Test a partial function created from another partial function"""

        def base_function(a, b, c):
            return a + b + c

        partial1 = functools.partial(base_function, a=1)
        partial2 = functools.partial(partial1, b=2)

        assert get_function_name(partial2) == "base_function"

    def test_callable_class_without_name(self):
        """Test function without __name__ attribute returns <unknown>"""

        class CallableClass:
            def __call__(self):
                return "called"

        callable_obj = CallableClass()
        assert get_function_name(callable_obj) == "<unknown>"

    def test_lambda_function(self):
        """Test lambda function name extraction"""
        lambda_func = lambda x: x * 2  # NOQA
        assert get_function_name(lambda_func) == "<lambda>"

    def test_partial_of_lambda(self):
        """Test a partial function created from lambda"""
        lambda_func = lambda x, y: x + y  # NOQA
        partial_lambda = functools.partial(lambda_func, x=5)

        assert get_function_name(partial_lambda) == "<lambda>"

    def test_method_partial(self):
        """Test a partial function created from a method"""

        class TestClass:
            def test_method(self, x, y):
                return x + y

        obj = TestClass()
        partial_method = functools.partial(obj.test_method, x=10)

        assert get_function_name(partial_method) == "test_method"

    def test_builtin_function(self):
        """Test built-in functions"""
        assert get_function_name(len) == "len"
        assert get_function_name(str) == "str"

    def test_partial_of_builtin(self):
        """Test partial function created from built-in function"""
        partial_int = functools.partial(int, base=16)
        assert get_function_name(partial_int) == "int"
