import sys
from pathlib import Path
from copy import copy

OPIK_API_KEY_PY_LINE = "# INJECT_OPIK_CONFIGURATION\n"

OPIK_CONFIGURATION_COMET_HOSTED = [
    'os.environ["OPIK_API_KEY"] = "${OPIK_API_KEY}"\n',
    'os.environ["OPIK_WORKSPACE"] = "${OPIK_WORKSPACE}"\n',
]
OPIK_CONFIGURATION_SELF_HOSTED = [
    'os.environ["COMET_URL_OVERRIDE"] = "${OPIK_URL_OVERRIDE}}'
]

FINAL_TEMPLATE = """// This file is auto-generated, do not edit manually

export const CODE_COMET_HOSTED = `{comet_code}`;

export const CODE_COMET_SELF_HOSTED = `{self_code}`;
"""


def convert_file(file_path: Path, output_path: Path) -> None:
    with file_path.open() as fd:
        file_lines = fd.readlines()

    # Double-check that os is imported
    assert "import os\n" in file_lines

    # Find where to inject the OPIK API Key
    opik_line = file_lines.index(OPIK_API_KEY_PY_LINE)

    comet_code = copy(file_lines)
    self_code = copy(file_lines)

    # Inject the configuration for Comet hosted code
    comet_code.pop(opik_line)

    for line in OPIK_CONFIGURATION_COMET_HOSTED:
        comet_code.insert(opik_line, line)

    # Inject the configuration for self hosted code
    self_code.pop(opik_line)

    for line in OPIK_CONFIGURATION_SELF_HOSTED:
        self_code.insert(opik_line, line)

    final_code = FINAL_TEMPLATE.format(
        comet_code="".join(comet_code), self_code="".join(self_code)
    )

    output_path.write_text(final_code)


if __name__ == "__main__":
    convert_file(Path(sys.argv[1]), Path(sys.argv[2]))
