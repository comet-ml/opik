"""
E2E test for code metric optimization - loads config from frontend, runs via backend factories.

Run with:
    cd sdks/opik_optimizer
    PYTHONPATH=src:../../../apps/opik-python-backend/src pytest tests/e2e/optimizers/test_code_metric_optimization.py -v

Requires:
    - OPENAI_API_KEY environment variable
    - Node.js (to parse frontend TypeScript)
"""

import json
import os
import subprocess
from pathlib import Path

import opik
import pytest

from opik_optimizer import ChatPrompt

pytestmark = pytest.mark.integration

# Path to frontend constants file
FRONTEND_CONSTANTS = Path(__file__).resolve().parents[5] / "apps/opik-frontend/src/constants/optimizations.ts"


def _load_frontend_template(template_id: str = "opik-chatbot") -> tuple[dict, list[dict]]:
    """Load studio_config and dataset_items from the frontend TypeScript file."""
    js_code = f'''
const fs = require('fs');
const content = fs.readFileSync('{FRONTEND_CONSTANTS}', 'utf-8');

// Replace TypeScript enums with string values
const processed = content
    .replace(/LLM_MESSAGE_ROLE\\.system/g, '"system"')
    .replace(/LLM_MESSAGE_ROLE\\.user/g, '"user"')
    .replace(/LLM_MESSAGE_ROLE\\.assistant/g, '"assistant"')
    .replace(/PROVIDER_MODEL_TYPE\\.[A-Z0-9_]+/g, '"gpt-4o-mini"')
    .replace(/METRIC_TYPE\\.CODE/g, '"code"')
    .replace(/METRIC_TYPE\\.G_EVAL/g, '"geval"')
    .replace(/METRIC_TYPE\\.EQUALS/g, '"equals"')
    .replace(/METRIC_TYPE\\.JSON_SCHEMA_VALIDATOR/g, '"json_schema_validator"')
    .replace(/METRIC_TYPE\\.LEVENSHTEIN/g, '"levenshtein_ratio"')
    .replace(/OPTIMIZER_TYPE\\.HIERARCHICAL_REFLECTIVE/g, '"hierarchical_reflective"')
    .replace(/OPTIMIZER_TYPE\\.GEPA/g, '"gepa"')
    .replace(/DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS\\.\\w+/g, '42')
    .replace(/DEFAULT_GEPA_OPTIMIZER_CONFIGS\\.\\w+/g, '42');

// Extract schema constant
const schemaMatch = processed.match(/const EXPECTED_JSON_SCHEMA\\s*=\\s*(\\{{[\\s\\S]*?\\}});\\n\\n/);
const EXPECTED_JSON_SCHEMA = schemaMatch ? eval('(' + schemaMatch[1] + ')') : {{}};

// Extract all datasets
const chatbotMatch = processed.match(/const CHATBOT_DATASET_ITEMS[^=]*=\\s*(\\[[\\s\\S]*?\\]);/);
const CHATBOT_DATASET_ITEMS = chatbotMatch ? eval(chatbotMatch[1]) : [];

const jsonMatch = processed.match(/const JSON_OUTPUT_DATASET_ITEMS[^=]*=\\s*(\\[[\\s\\S]*?\\]);/);
const JSON_OUTPUT_DATASET_ITEMS = jsonMatch ? eval(jsonMatch[1]) : [];

// Extract templates array  
const templatesMatch = processed.match(/OPTIMIZATION_DEMO_TEMPLATES[^=]*=\\s*(\\[[\\s\\S]*?\\]);/s);
if (!templatesMatch) {{ console.error("Templates not found"); process.exit(1); }}

const templates = eval(templatesMatch[1]);
const template = templates.find(t => t.id === "{template_id}");
if (!template) {{ console.error("Template not found"); process.exit(1); }}

console.log(JSON.stringify({{
    studio_config: template.studio_config,
    dataset_items: template.dataset_items
}}));
'''
    result = subprocess.run(["node", "-e", js_code], capture_output=True, text=True)
    if result.returncode != 0:
        pytest.skip(f"Failed to parse frontend: {result.stderr}")

    data = json.loads(result.stdout)
    return data["studio_config"], data["dataset_items"]


def test_optimization_with_code_metric() -> None:
    """Run optimization using config loaded from frontend."""
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY is required")

    # Import backend (skip if not available)
    try:
        from opik_backend.studio.metrics import MetricFactory
        from opik_backend.studio.optimizers import OptimizerFactory
        from opik_backend.studio.types import OptimizationConfig
    except ImportError:
        pytest.skip("opik_backend not in PYTHONPATH - run with PYTHONPATH including apps/opik-python-backend/src")

    # Load config from frontend
    studio_config, dataset_items = _load_frontend_template("opik-chatbot")

    # Parse config (same as backend)
    config = OptimizationConfig.from_dict(studio_config)

    # Build metric (same as backend)
    metric_fn = MetricFactory.build(config.metric_type, config.metric_params, config.model)

    # Build optimizer (same as backend)
    optimizer = OptimizerFactory.build(config.optimizer_type, config.model, config.model_params, config.optimizer_params)

    # Create prompt (same as backend)
    prompt = ChatPrompt(messages=config.prompt_messages)

    # Create dataset
    client = opik.Opik()
    dataset_name = "test_code_metric_e2e"

    try:
        try:
            client.get_dataset(dataset_name).delete()
        except Exception:
            pass

        dataset = client.create_dataset(dataset_name)
        dataset.insert(dataset_items)

        # Run optimization
        result = optimizer.optimize_prompt(prompt=prompt, dataset=dataset, metric=metric_fn, n_samples=len(dataset_items))

        assert result is not None
        assert result.prompt is not None
        assert result.score is not None

    finally:
        try:
            client.get_dataset(dataset_name).delete()
        except Exception:
            pass
