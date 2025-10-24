from flask import Blueprint, request, jsonify
import opik
import time

datasets_bp = Blueprint('datasets', __name__)

def get_opik_client():
    return opik.Opik()

@datasets_bp.route('/api/datasets/create', methods=['POST'])
def create_dataset():
    data = request.json
    dataset_name = data.get('name')

    client = get_opik_client()
    dataset = client.create_dataset(name=dataset_name)

    return jsonify({'id': dataset.id, 'name': dataset.name})

@datasets_bp.route('/api/datasets/find', methods=['POST'])
def find_dataset():
    data = request.json
    dataset_name = data.get('name')

    client = get_opik_client()
    try:
        dataset = client.get_dataset(dataset_name)
        return jsonify({
            'id': dataset.id,
            'name': dataset.name
        })
    except Exception as e:
        if '404' in str(e) or 'not found' in str(e).lower():
            return jsonify(None), 404
        raise

@datasets_bp.route('/api/datasets/update', methods=['POST'])
def update_dataset():
    data = request.json
    dataset_name = data.get('name')
    new_name = data.get('newName')

    client = get_opik_client()
    dataset = client.get_dataset(dataset_name)
    dataset_id = dataset.id

    from opik.rest_api.client import OpikApi
    api_client = OpikApi()
    api_client.datasets.update_dataset(id=dataset_id, name=new_name)

    return jsonify({'id': dataset_id, 'name': new_name})

@datasets_bp.route('/api/datasets/delete', methods=['DELETE'])
def delete_dataset():
    data = request.json
    dataset_name = data.get('name')

    client = get_opik_client()
    try:
        client.delete_dataset(dataset_name)
        return jsonify({'success': True})
    except Exception as e:
        if '404' in str(e) or 'not found' in str(e).lower():
            return jsonify({'success': True})
        raise

@datasets_bp.route('/api/datasets/wait-for-visible', methods=['POST'])
def wait_for_dataset_visible():
    data = request.json
    dataset_name = data.get('name')
    timeout = data.get('timeout', 10)

    client = get_opik_client()
    start_time = time.time()
    dataset = None

    while time.time() - start_time < timeout:
        try:
            dataset = client.get_dataset(dataset_name)
            if dataset:
                return jsonify({
                    'id': dataset.id,
                    'name': dataset.name
                })
        except Exception:
            pass
        time.sleep(0.5)

    return jsonify({'error': f'Dataset {dataset_name} not visible after {timeout}s'}), 404

@datasets_bp.route('/api/datasets/wait-for-deleted', methods=['POST'])
def wait_for_dataset_deleted():
    data = request.json
    dataset_name = data.get('name')
    timeout = data.get('timeout', 10)

    client = get_opik_client()
    start_time = time.time()

    while time.time() - start_time < timeout:
        try:
            client.get_dataset(dataset_name)
            time.sleep(0.5)
        except Exception as e:
            if '404' in str(e) or 'not found' in str(e).lower():
                return jsonify({'success': True})

    return jsonify({'error': f'Dataset {dataset_name} still exists after {timeout}s'}), 400
