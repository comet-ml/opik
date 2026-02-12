import json
from pathlib import Path

import jsonschema


ROOT_DIR = Path(__file__).resolve().parents[3]
SCHEMA_PATH = ROOT_DIR / "benchmarks" / "configs" / "manifest.schema.json"
TASKS_EXAMPLE_PATH = ROOT_DIR / "scripts" / "benchmarks" / "tasks.example.json"
GENERATOR_EXAMPLE_PATH = ROOT_DIR / "scripts" / "benchmarks" / "generator.example.json"


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def test_tasks_example_validates_against_manifest_schema() -> None:
    schema = _load_json(SCHEMA_PATH)
    payload = _load_json(TASKS_EXAMPLE_PATH)
    jsonschema.validate(instance=payload, schema=schema)


def test_generator_example_validates_against_manifest_schema() -> None:
    schema = _load_json(SCHEMA_PATH)
    payload = _load_json(GENERATOR_EXAMPLE_PATH)
    jsonschema.validate(instance=payload, schema=schema)
