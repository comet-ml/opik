import logging
import os
import sys

from flask import Flask, jsonify
from opentelemetry import metrics, trace

from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter

from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry._logs import set_logger_provider

from werkzeug.exceptions import HTTPException

from opik_backend.http_utils import build_error_response


# Note: All auto-instrumentation is handled by 'opentelemetry-instrument' command in entrypoint.sh

def create_app(test_config=None, should_init_executor=True):
    app = Flask(__name__, instance_relative_config=True)

    # Configure logging
    gunicorn_logger = logging.getLogger('gunicorn.error')
    if gunicorn_logger.handlers and len(gunicorn_logger.handlers) > 0:
        app.logger.handlers = gunicorn_logger.handlers
        app.logger.setLevel(gunicorn_logger.level)
    else:
        # this logger is used mainly for debugging, we don't want it to raise exceptions
        logging.raiseExceptions = False
        # Fallback basic logging if not running under Gunicorn
        if not app.logger.handlers:
            console_handler = logging.StreamHandler(sys.stderr)
            formatter = logging.Formatter(
                '%(asctime)s %(levelname)s [%(name)s] [%(filename)s:%(lineno)d] - %(message)s'
            )
            console_handler.setFormatter(formatter)
            app.logger.addHandler(console_handler)
        app.logger.setLevel(logging.DEBUG if app.debug else logging.INFO)

    if test_config is None:
        # load the instance config, if it exists, when not testing
        app.config.from_pyfile('config.py', silent=True)
    else:
        # load the test config if passed in
        app.config.from_mapping(test_config)

    # Log SDK versions at startup for debugging
    try:
        import opik
        app.logger.info(f"opik SDK version: {opik.__version__}")
    except Exception as e:
        app.logger.warning(f"Could not determine opik SDK version: {e}")
    
    try:
        import opik_optimizer
        app.logger.info(f"opik_optimizer SDK version: {opik_optimizer.__version__}")
    except Exception as e:
        app.logger.warning(f"Could not determine opik_optimizer SDK version: {e}")

    # Setup OpenTelemetry before registering blueprints
    if os.environ.get("OPIK_OTEL_SDK_ENABLED", "").lower() == "true":
        setup_telemetry(app)

    from opik_backend.evaluator import evaluator, init_executor
    from opik_backend.post_user_signup import post_user_signup
    from opik_backend.healthcheck import healthcheck
    from opik_backend.rq_worker_manager import init_rq_worker

    # Initialize the code executor if needed - some of the tests override the executor and therefore don't initialize it
    if should_init_executor:
        init_executor(app)

    app.register_blueprint(healthcheck)
    app.register_blueprint(evaluator)
    app.register_blueprint(post_user_signup)

    # Initialize Redis connection at application startup if RQ worker enabled (non-fatal)
    from opik_backend.utils.env_utils import is_rq_worker_enabled
    if is_rq_worker_enabled():
        try:
            from opik_backend.utils.redis_utils import get_redis_client
            get_redis_client().ping()
            app.logger.info("Redis client initialized at startup")

            # Initialize RQ worker (only starts when running under Gunicorn)
            init_rq_worker(app)
        except Exception as e:
            app.logger.warning(f"Redis client initialization failed at startup: {e}")

    # Ensure Redis client is closed at teardown
    from opik_backend.utils.redis_utils import get_redis_client
    import atexit

    @app.teardown_appcontext
    def close_redis_client(exception):
        # Do NOT close when worker is enabled (worker uses shared client)
        if not is_rq_worker_enabled():
            try:
                client = get_redis_client()
                client.close()
            except Exception as e:
                app.logger.warning(f"Error closing Redis client: {e}")

    # Also close on process exit
    def _close_redis_on_exit():
        try:
            client = get_redis_client()
            client.close()
        except Exception as e:
            app.logger.warning(f"Error closing Redis client: {e}")

    atexit.register(_close_redis_on_exit)

    @app.errorhandler(HTTPException)
    def handle_http_exception(http_exception):
        return build_error_response(http_exception, http_exception.code)

    @app.errorhandler(Exception)
    def handle_exception(exception):
        app.logger.exception("Unhandled exception occurred")
        message = str(exception) if app.debug else "Something went wrong. Please try again later."
        response = jsonify({
            "error": "Internal Server Error",
            "message": message
        })
        response.status_code = 500
        return response

    return app

def setup_otel_metrics(app, resource):
    """Configure OpenTelemetry metrics export."""
    otlp_reader = PeriodicExportingMetricReader(OTLPMetricExporter())
    provider = MeterProvider(resource=resource, metric_readers=[otlp_reader])
    metrics.set_meter_provider(provider)
    app.logger.debug("OpenTelemetry metrics configured")

def setup_otel_traces(app, resource):
    """Configure OpenTelemetry traces export.""" 
    trace_provider = TracerProvider(resource=resource)
    trace_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(trace_provider)
    app.logger.debug("OpenTelemetry traces configured")

def setup_otel_logs(app, resource):
    """Configure OpenTelemetry logs export and return the logging handler."""
    logger_provider = LoggerProvider(resource=resource)
    logger_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter()))
    set_logger_provider(logger_provider)
    
    # Create and configure OpenTelemetry logging handler
    handler = LoggingHandler(logger_provider=logger_provider)
    
    # Set OpenTelemetry handler level based on environment or app debug mode
    otel_log_level_str = os.getenv('OTEL_PYTHON_LOG_LEVEL', 'DEBUG' if app.debug else 'INFO')
    try:
        otel_log_level = getattr(logging, otel_log_level_str.upper())
    except AttributeError:
        otel_log_level = logging.INFO
        app.logger.warning(f"Invalid OTEL_PYTHON_LOG_LEVEL '{otel_log_level_str}', defaulting to INFO")
    
    handler.setLevel(otel_log_level)
    app.logger.info(f"OpenTelemetry log level set to: {logging.getLevelName(otel_log_level)}")
    
    return handler

def setup_console_logging(app):
    """Configure console logging levels to control verbosity."""
    console_log_level_str = os.getenv('OPIK_CONSOLE_LOG_LEVEL', 'INFO')
    try:
        console_log_level = getattr(logging, console_log_level_str.upper())
    except AttributeError:
        console_log_level = logging.INFO
        app.logger.warning(f"Invalid OPIK_CONSOLE_LOG_LEVEL '{console_log_level_str}', defaulting to INFO")
    
    # Set root logger level to control all third-party library logging
    # This is cleaner than maintaining hardcoded logger lists
    root_logger = logging.getLogger()
    if console_log_level > root_logger.level or root_logger.level == logging.NOTSET:
        root_logger.setLevel(console_log_level)
        app.logger.info(f"Console log level set to: {logging.getLevelName(console_log_level)}")



def setup_telemetry(app):
    """Configure OpenTelemetry metrics, traces, and logs using OTLP export."""
    # Check if OTLP endpoint is configured
    otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not otlp_endpoint:
        app.logger.warning("No OTLP endpoint configured. Metrics will not be exported.")
        return

    app.logger.info(f"Configured OTLP endpoint: {otlp_endpoint}. Will push metrics to this endpoint.")

    # Create shared resource for all telemetry signals
    resource = Resource.create()

    # Set up each telemetry signal
    setup_otel_metrics(app, resource)
    setup_otel_traces(app, resource)
    otel_handler = setup_otel_logs(app, resource)
    
    # Configure logging handlers
    # Set root logger to NOTSET to allow all messages through to handlers
    # This ensures OpenTelemetry can receive all log levels, as recommended by:
    # https://markandruth.co.uk/2025/05/30/getting-opentelemetry-logging-working-in-python
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.NOTSET)
    root_logger.addHandler(otel_handler)
    
    # Also add handler to Flask app logger for guaranteed coverage
    app.logger.addHandler(otel_handler)
    
    # Configure console logging levels
    setup_console_logging(app)
    
    # Note: Auto-instrumentation is handled by 'opentelemetry-instrument' command in entrypoint.sh
    # No manual instrumentation needed here
