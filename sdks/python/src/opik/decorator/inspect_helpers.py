import inspect

from typing import Callable, Tuple, Any, Dict


def extract_inputs(
    func: Callable, args: Tuple, kwargs: Dict[str, Any]
) -> Dict[str, Any]:
    sig = inspect.signature(func)

    bound_args = sig.bind(*args, **kwargs)  # type: ignore
    bound_args.apply_defaults()

    arg_dict = dict(bound_args.arguments)

    if "self" in arg_dict:
        arg_dict.pop("self")
    elif "cls" in arg_dict:
        arg_dict.pop("cls")

    return arg_dict


def is_generator(obj: Any) -> bool:
    return inspect.isgenerator(obj)


def is_async(func: Callable) -> bool:
    if inspect.iscoroutinefunction(func):
        return True

    # Usual iscoroutinefunction might not work correctly if
    # async function is wrapped.
    if hasattr(func, "__wrapped__") and inspect.iscoroutinefunction(func.__wrapped__):
        return True

    return False
