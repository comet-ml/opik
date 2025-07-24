import logging
import os
import sys

from flask import Flask
from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.instrumentation.flask import FlaskInstrumentor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource


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

    # Setup OpenTelemetry before registering blueprints
    if os.environ.get("OPIK_OTEL_SDK_ENABLED") == "true":
        setup_telemetry(app)

    from opik_backend.evaluator import evaluator, init_executor
    from opik_backend.post_user_signup import post_user_signup
    from opik_backend.healthcheck import healthcheck

    # Initialize the code executor if needed - some of the tests override the executor and therefore don't initialize it
    if should_init_executor:
        init_executor(app)

    app.register_blueprint(healthcheck)
    app.register_blueprint(evaluator)
    app.register_blueprint(post_user_signup)

    return app

def setup_telemetry(app):
    """Configure OpenTelemetry metrics for the application using OTLP push metrics only."""
    # Check if OTLP endpoint is configured
    otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not otlp_endpoint:
        app.logger.warning("No OTLP endpoint configured. Metrics will not be exported.")
        return
    
    app.logger.info(f"Configured OTLP endpoint: {otlp_endpoint}. Will push metrics to this endpoint.")
    
    # Create OTLP reader for pushing metrics
    otlp_reader = PeriodicExportingMetricReader(OTLPMetricExporter())
    
    # Create MeterProvider with OTLP reader only
    resource = Resource.create()
    provider = MeterProvider(resource=resource, metric_readers=[otlp_reader])

    # Set the global MeterProvider
    metrics.set_meter_provider(provider)
    
    # Configure Flask instrumentation
    FlaskInstrumentor().instrument_app(app)
