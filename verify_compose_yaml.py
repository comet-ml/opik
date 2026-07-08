"""
Verification script for Opik Docker Compose deployment configurations.
This script validates that the deployment/docker-compose/docker-compose.yaml file
is syntactically valid and contains the correct parameterized platform properties.
"""

import yaml
import sys

def verify():
    """
    Validates that the target docker-compose.yaml is correct.
    Checks that 'demo-data-generator' and 'guardrails-backend' have
    platform variables configured, and exits with status 1 on mismatch.
    """
    path = "deployment/docker-compose/docker-compose.yaml"
    print(f"=== Verifying {path} syntax and values ===")
    try:
        with open(path, "r", encoding="utf-8") as f:
            content = yaml.safe_load(f)
            
        services = content.get("services", {})
        
        # 1. Check demo-data-generator
        demo = services.get("demo-data-generator", {})
        platform_demo = demo.get("platform")
        print(f"demo-data-generator platform: {platform_demo}")
        assert platform_demo == "${OPIK_PYTHON_BACKEND_PLATFORM:-}", "demo-data-generator platform is incorrect!"
        
        # 2. Check guardrails-backend
        guardrails = services.get("guardrails-backend", {})
        platform_guardrails = guardrails.get("platform")
        print(f"guardrails-backend platform: {platform_guardrails}")
        assert platform_guardrails == "${OPIK_GUARDRAILS_BACKEND_PLATFORM:-}", "guardrails-backend platform is incorrect!"
        
        print("\nVerification Successful! YAML is syntactically valid and platform properties are correct.")
    except Exception as e:
        print("Verification Failed:", e)
        sys.exit(1)

if __name__ == "__main__":
    verify()
