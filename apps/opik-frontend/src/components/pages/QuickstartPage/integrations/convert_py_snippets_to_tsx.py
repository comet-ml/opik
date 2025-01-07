import sys
from pathlib import Path

OPIK_API_KEY_PY_LINE = "# INJECT_OPIK_API_KEY\n"
OPIK_API_KEY_TSX_LINE = 'os.environ["OPIK_API_KEY"] = "${OPIK_API_KEY}"'

FINAL_TEMPLATE = """
const CODE = `{code}`

export default CODE;
"""


def convert_file(file_path: Path, output_path: Path) -> None:
    with file_path.open() as fd:
        file_lines = fd.readlines()

    # Find where to inject the OPIK API Key
    opik_line = file_lines.index(OPIK_API_KEY_PY_LINE)

    # Replace the line
    file_lines[opik_line] = OPIK_API_KEY_TSX_LINE

    # Double-check that os is imported
    assert "import os\n" in file_lines

    final_code = FINAL_TEMPLATE.format(code="".join(file_lines))

    output_path.write_text(final_code)


if __name__ == "__main__":
    convert_file(Path(sys.argv[1]), Path(sys.argv[2]))
