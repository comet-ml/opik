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
    app.register_blueprint(evaluator)

    # TODO: optimize creation e.g: at service build time
    from opik_backend.docker_runner import \
        create_docker_image, \
        PYTHON_CODE_EXECUTOR_DOCKERFILE, \
        PYTHON_CODE_EXECUTOR_IMAGE_NAME_AND_TAG
    create_docker_image(PYTHON_CODE_EXECUTOR_DOCKERFILE, PYTHON_CODE_EXECUTOR_IMAGE_NAME_AND_TAG, )

    return app
