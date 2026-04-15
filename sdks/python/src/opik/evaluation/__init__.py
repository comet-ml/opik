_LAZY_IMPORTS: dict = {
    "evaluate": (".evaluator", "evaluate"),
    "evaluate_prompt": (".evaluator", "evaluate_prompt"),
    "evaluate_experiment": (".evaluator", "evaluate_experiment"),
    "evaluate_on_dict_items": (".evaluator", "evaluate_on_dict_items"),
    "evaluate_optimization_trial": (".evaluator", "evaluate_optimization_trial"),
    "run_tests": (".evaluator", "run_tests"),
    "evaluate_threads": (".threads.evaluator", "evaluate_threads"),
}

__all__ = [
    "evaluate",
    "evaluate_prompt",
    "evaluate_experiment",
    "evaluate_on_dict_items",
    "evaluate_optimization_trial",
    "evaluate_threads",
    "run_tests",
]


def __getattr__(name: str) -> object:
    if name in _LAZY_IMPORTS:
        module_path, attr_name = _LAZY_IMPORTS[name]

        import importlib

        module = importlib.import_module(module_path, __package__)
        value = getattr(module, attr_name)

        globals()[name] = value
        return value

    raise AttributeError(f"module 'opik.evaluation' has no attribute {name!r}")


def __dir__() -> list[str]:
    return list(__all__)
