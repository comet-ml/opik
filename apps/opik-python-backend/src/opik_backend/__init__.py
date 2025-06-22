import logging
import os
import sys

from flask import Flask, make_response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.prometheus import PrometheusMetricReader
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
    """Configure OpenTelemetry metrics for the application."""
    # Create metric readers based on environment
    metric_readers = []

    # Always add Prometheus reader for k8s scraping
    prometheus_reader = PrometheusMetricReader()
    metric_readers.append(prometheus_reader)
    
    # Add OTLP reader if endpoint is configured
    otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if otlp_endpoint:
        app.logger.info(f"Configured OTLP endpoint: {otlp_endpoint}. Will push metrics to this endpoint.")
        otlp_reader = PeriodicExportingMetricReader(OTLPMetricExporter())
        metric_readers.append(otlp_reader)
    else:
        app.logger.info("No OTLP endpoint configured. Will not push metrics.")

    # Create MeterProvider with all readers
    resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "opik-python-backend")})
    provider = MeterProvider(resource=resource, metric_readers=metric_readers)

    # Set the global MeterProvider
    metrics.set_meter_provider(provider)
    
    # Configure Flask instrumentation to exclude metrics endpoint
    FlaskInstrumentor().instrument_app(
        app,
        excluded_urls="/metrics"
    )
    
    # Add Prometheus metrics endpoint
    @app.route("/metrics")
    def prometheus_metrics():
        """Endpoint for Prometheus metrics scraping."""
        return make_response(generate_latest(), 200, {'Content-Type': CONTENT_TYPE_LATEST})
