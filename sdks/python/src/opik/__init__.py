from . import package_version

__version__ = package_version.VERSION

# Mapping of public names to their (module_path, attribute_name) for lazy loading.
# Imports are deferred until first access via __getattr__ (PEP 562).
_LAZY_IMPORTS: dict = {
    # api_objects
    "TracesAnnotationQueue": (".api_objects.annotation_queue", "TracesAnnotationQueue"),
    "ThreadsAnnotationQueue": (
        ".api_objects.annotation_queue",
        "ThreadsAnnotationQueue",
    ),
    "Attachment": (".api_objects.attachment", "Attachment"),
    "Dataset": (".api_objects.dataset", "Dataset"),
    "TestSuite": (".api_objects.dataset.test_suite", "TestSuite"),
    "TestSuiteResult": (".api_objects.dataset.test_suite.types", "TestSuiteResult"),
    "ExperimentItemContent": (
        ".api_objects.experiment.experiment_item",
        "ExperimentItemContent",
    ),
    "ExperimentItemReferences": (
        ".api_objects.experiment.experiment_item",
        "ExperimentItemReferences",
    ),
    "Config": (".api_objects.agent_config", "Config"),
    "Blueprint": (".api_objects.agent_config", "Blueprint"),
    "agent_config_context": (
        ".api_objects.agent_config.context",
        "agent_config_context",
    ),
    "ConfigNotFound": (".exceptions", "ConfigNotFound"),
    "ConfigMismatch": (".exceptions", "ConfigMismatch"),
    "Opik": (".api_objects.opik_client", "Opik"),
    "get_global_client": (".api_objects.opik_client", "get_global_client"),
    "set_global_client": (".api_objects.opik_client", "set_global_client"),
    "Prompt": (".api_objects.prompt", "Prompt"),
    "ChatPrompt": (".api_objects.prompt", "ChatPrompt"),
    "PromptType": (".api_objects.prompt.types", "PromptType"),
    "Span": (".api_objects.span", "Span"),
    "Trace": (".api_objects.trace", "Trace"),
    "record_traces_locally": (".api_objects.local_recording", "record_traces_locally"),
    # configurator
    "configure": (".configurator.configure", "configure"),
    # decorator
    "flush_tracker": (".decorator.tracker", "flush_tracker"),
    "track": (".decorator.tracker", "track"),
    "start_as_current_span": (
        ".decorator.context_manager.span_context_manager",
        "start_as_current_span",
    ),
    "start_as_current_trace": (
        ".decorator.context_manager.trace_context_manager",
        "start_as_current_trace",
    ),
    # evaluation
    "evaluate": (".evaluation", "evaluate"),
    "evaluate_experiment": (".evaluation", "evaluate_experiment"),
    "evaluate_on_dict_items": (".evaluation", "evaluate_on_dict_items"),
    "evaluate_prompt": (".evaluation", "evaluate_prompt"),
    "run_tests": (".evaluation", "run_tests"),
    # types
    "LLMProvider": (".types", "LLMProvider"),
    # tracing runtime config
    "is_tracing_active": (".tracing_runtime_config", "is_tracing_active"),
    "reset_tracing_to_config_default": (
        ".tracing_runtime_config",
        "reset_tracing_to_config_default",
    ),
    "set_tracing_active": (".tracing_runtime_config", "set_tracing_active"),
    # simulation
    "SimulatedUser": (".simulation", "SimulatedUser"),
    "run_simulation": (".simulation", "run_simulation"),
    # context
    "project_context": (".context_storage", "project_context"),
    "update_current_trace": (".opik_context", "update_current_trace"),
    "update_current_span": (".opik_context", "update_current_span"),
    # submodules accessed as opik.opik_context
    "opik_context": (".opik_context", None),
    # plugins
    "llm_unit": (".plugins.pytest.decorator", "llm_unit"),
}

__all__ = [
    "__version__",
    "TracesAnnotationQueue",
    "ThreadsAnnotationQueue",
    "Attachment",
    "evaluate",
    "evaluate_prompt",
    "evaluate_experiment",
    "evaluate_on_dict_items",
    "run_tests",
    "ExperimentItemContent",
    "ExperimentItemReferences",
    "track",
    "flush_tracker",
    "Opik",
    "get_global_client",
    "set_global_client",
    "opik_context",
    "Trace",
    "Span",
    "Dataset",
    "TestSuite",
    "TestSuiteResult",
    "llm_unit",
    "configure",
    "Prompt",
    "ChatPrompt",
    "PromptType",
    "LLMProvider",
    "reset_tracing_to_config_default",
    "set_tracing_active",
    "is_tracing_active",
    "start_as_current_span",
    "start_as_current_trace",
    "SimulatedUser",
    "run_simulation",
    "record_traces_locally",
    "Config",
    "ConfigNotFound",
    "ConfigMismatch",
    "Blueprint",
    "agent_config_context",
    "update_current_trace",
    "update_current_span",
    "project_context",
]

_initialized = False


def _ensure_initialized() -> None:
    """Run one-time SDK setup (logging, sentry, sagemaker) on first real use."""
    global _initialized
    if _initialized:
        return
    _initialized = True

    from . import _logging, environment, error_tracking

    _logging.setup()

    from .integrations.sagemaker import auth as sagemaker_auth

    sagemaker_auth.setup_aws_sagemaker_session_hook()

    if (
        error_tracking.enabled_in_config()
        and not environment.in_pytest()
        and error_tracking.randomized_should_enable_reporting()
    ):
        error_tracking.setup_sentry_error_tracker()


def __getattr__(name: str) -> object:
    if name in _LAZY_IMPORTS:
        _ensure_initialized()

        module_path, attr_name = _LAZY_IMPORTS[name]

        import importlib

        module = importlib.import_module(module_path, __package__)
        value = getattr(module, attr_name) if attr_name is not None else module

        # Cache in module globals so __getattr__ is not called again for this name
        globals()[name] = value
        return value

    raise AttributeError(f"module 'opik' has no attribute {name!r}")


def __dir__() -> list[str]:
    return list(__all__) + list(_LAZY_IMPORTS.keys())
