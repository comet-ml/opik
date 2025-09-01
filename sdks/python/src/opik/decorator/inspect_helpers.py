import inspect
import functools

from typing import Callable, Tuple, Any, Dict


def extract_inputs(
    func: Callable, args: Tuple, kwargs: Dict[str, Any]
) -> Dict[str, Any]:
    sig = inspect.signature(func)

    try:
        bound_args = sig.bind(*args, **kwargs)  # type: ignore
        bound_args.apply_defaults()
        arg_dict = dict(bound_args.arguments)
    except TypeError:
        arg_dict = {
            "args": args,
            "kwargs": kwargs,
        }

    if "self" in arg_dict:
        arg_dict.pop("self")
    elif "cls" in arg_dict:
        arg_dict.pop("cls")

    return arg_dict


def is_generator(obj: Any) -> bool:
    return inspect.isgenerator(obj)


def is_generator_function(obj: Any) -> bool:
    return inspect.isgeneratorfunction(obj)


def is_async(func: Callable) -> bool:
    if inspect.iscoroutinefunction(func):
        return True

    # Usual iscoroutinefunction might not work correctly if
    # async function is wrapped.
    if hasattr(func, "__wrapped__") and inspect.iscoroutinefunction(func.__wrapped__):
        return True

    return False


def get_function_name(func: Callable) -> str:
    """Safely get the name of a function, handling functools.partial objects."""
    if isinstance(func, functools.partial):
        # For partial objects, get the name from the underlying function
        return getattr(func.func, "__name__", "<unknown>")

    # For regular functions and other callables
    return getattr(func, "__name__", "<unknown>")
