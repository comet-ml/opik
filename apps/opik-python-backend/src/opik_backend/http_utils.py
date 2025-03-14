
from flask import jsonify
from werkzeug.exceptions import HTTPException

def build_error_response(exception: HTTPException, status_code: int):
    return jsonify(error=str(exception)), status_code
