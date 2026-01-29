from flask import Flask, jsonify, request

from .kv_store import KVStore

app = Flask(__name__)
store = KVStore()


@app.after_request
def add_cors_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    return response


@app.route("/health")
def health():
    return jsonify({"status": "healthy"})


@app.route("/config/get", methods=["POST"])
def get_config():
    data = request.get_json()
    if not data or "keys" not in data:
        return jsonify({"error": "Missing 'keys'"}), 400

    keys = data["keys"]
    experiment_id = data.get("experiment_id")

    results = store.get_batch(keys, experiment_id=experiment_id)

    values = {}
    for key, config_value in results.items():
        if config_value is not None:
            values[key] = config_value.to_dict()
        else:
            values[key] = None

    return jsonify({"values": values})


@app.route("/config/set", methods=["POST"])
def set_config():
    data = request.get_json()
    if not data or "key" not in data or "value" not in data:
        return jsonify({"error": "Missing 'key' or 'value'"}), 400

    key = data["key"]
    value = data["value"]
    experiment_id = data.get("experiment_id")
    if_not_exists = data.get("if_not_exists", False)
    is_default = data.get("is_default", False)

    store_key = f"{key}:{experiment_id}" if experiment_id else key

    if if_not_exists:
        existing = store.get_batch([store_key])
        if store_key in existing and existing[store_key] is not None:
            return jsonify(existing[store_key].to_dict()), 200

    # If this is the default registration, store the fallback in metadata
    metadata = {"fallback": value} if is_default else None
    config_value = store.set(store_key, value, metadata=metadata)
    return jsonify(config_value.to_dict()), 201


@app.route("/config/list")
def list_config():
    all_values = store.list_all()
    return jsonify({key: cv.to_dict() for key, cv in all_values.items()})


def run_service(host: str = "127.0.0.1", port: int = 5050, debug: bool = False) -> None:
    app.run(host=host, port=port, debug=debug)
