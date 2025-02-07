import yaml
import sys
from pathlib import Path


def add_auth_components(data):
    # Add parameters to components if they don't exist
    if "components" not in data:
        data["components"] = {}
    if "parameters" not in data["components"]:
        data["components"]["parameters"] = {}

    parameters = {
        "ApiKeyHeader": {
            "name": "authorization",
            "in": "header",
            "required": False,
            "schema": {"type": "string"},
            "description": "If using Opik cloud, provide your API key. Otherwise, leave empty.",
        },
        "WorkspaceHeader": {
            "name": "Comet-Workspace",
            "in": "header",
            "required": False,
            "schema": {"type": "string"},
            "description": "If using Opik cloud, provide your workspace name. Otherwise, leave empty.",
        },
    }

    data["components"]["parameters"].update(parameters)


def patch_loader(loader: yaml.SafeLoader) -> yaml.SafeLoader:
    """
    Patch the loader to allow = as a value.
    See https://github.com/yaml/pyyaml/issues/89 for more info.
    :return: The patched YAML loader.
    """
    loader.yaml_implicit_resolvers.pop("=")
    return loader


def add_auth_parameters(yaml_file):
    loader = yaml.SafeLoader
    patched_loader = patch_loader(loader)

    with open(yaml_file, "r") as f:
        data = yaml.load(f, Loader=patched_loader)

    # Add security components
    add_auth_components(data)

    auth_params = [
        {"$ref": "#/components/parameters/WorkspaceHeader"},
        {"$ref": "#/components/parameters/ApiKeyHeader"},
    ]

    # Iterate through all paths
    for path, methods in data["paths"].items():
        if path.startswith("/v1/"):
            # Iterate through all HTTP methods (get, post, etc.)
            for method_data in methods.values():
                if "parameters" not in method_data:
                    method_data["parameters"] = []
                # Add auth parameters directly without anchors
                for auth_param in auth_params:
                    if auth_param not in method_data["parameters"]:
                        method_data["parameters"].append(
                            dict(auth_param)
                        )  # Use dict() to create a new copy

    # Write back to file
    output_path = yaml_file.parent / f"patched_{yaml_file.name}"
    with open(output_path, "w") as f:
        yaml.dump(data, f)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python edit_yaml_file.py <path_to_yaml_file>")
        sys.exit(1)

    yaml_file = Path(sys.argv[1])
    if not yaml_file.exists():
        print(f"Error: File {yaml_file} does not exist")
        sys.exit(1)

    add_auth_parameters(yaml_file)
