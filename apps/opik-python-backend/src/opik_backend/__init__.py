from flask import Flask


def create_app(test_config=None):
    app = Flask(__name__, instance_relative_config=True)

    if test_config is None:
        # load the instance config, if it exists, when not testing
        app.config.from_pyfile('config.py', silent=True)
    else:
        # load the test config if passed in
        app.config.from_mapping(test_config)

    from opik_backend.evaluator import evaluator
    from opik_backend.post_user_signup import post_user_signup
    app.register_blueprint(evaluator)
    app.register_blueprint(post_user_signup)

    return app
