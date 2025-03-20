import flask

def create_app():
    app = flask.Flask(__name__)
    
    from .api import routes
    app.register_blueprint(routes.guardrails_blueprint)
    
    return app