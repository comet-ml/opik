import flask


def create_app() -> flask.Flask:
    app = flask.Flask(__name__)

    from .api import routes

    app.register_blueprint(routes.guardrails_blueprint)
    app.register_blueprint(routes.healthcheck)

    return app
