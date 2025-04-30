from mrkdwn_analysis import MarkdownAnalyzer
import logging
from . import evaluators
from typing import List, Union, Optional

LOGGER = logging.getLogger(__name__)


def _get_code_block_language(language: Optional[str]):
    """
    This method extracts the language of the code block based on the string that is
    after ``` in each code block.
    """
    if language is None:
        return None
    params = language.split(" ")
    if (len(params) == 1) and params[0] == "":
        return None
    else:
        return params[0]


def _reindent_code_block(code_block):
    first_line = code_block.split("\n")[0]
    leading_spaces = len(first_line) - len(first_line.lstrip())

    return "\n".join([x[leading_spaces:] for x in code_block.split("\n")])


def get_page_frontmatter(path):
    headers = {}
    with open(path, "r") as f:
        lines = f.read().split("\n")
        if lines[0] != "---":
            return headers
        else:
            for line in lines[1:]:
                if line.startswith("---"):
                    break
                if ":" in line:
                    _ = line.split(":")
                    k = ":".join(_[:-1])
                    v = _[-1]
                    k = k.strip()
                    if v == "true" or v == "True":
                        v = True
                    if v == "false" or v == "False":
                        v = False
                    headers[k] = v
    return headers


def check_skip_code_block(mk_language):
    language_params = mk_language.split(" ")
    for params in language_params:
        params = params.strip("{").strip("}")
        if "=" in params:
            title, value = params.split("=")
            if title == "pytest_codeblocks_skip" and (
                value.strip() == "true" or value.strip() == "True"
            ):
                return True

    return False


def check_skip_frontmatter(path):
    frontmatter = get_page_frontmatter(path)
    return frontmatter.get("pytest_codeblocks_skip", False)


def convert_jupyter_pip_install_to_bash(code_block):
    if "%pip install" in code_block["content"]:
        code_block["language"] = "bash"
        code_block["content"] = code_block["content"].replace("%pip", "pip")

    return code_block


def get_code_blocs(
    path: str,
) -> List[Union[evaluators.PythonEvaluator, evaluators.BashEvaluator]]:
    LOGGER.debug(f"Finding code blocks in {path}")

    if check_skip_frontmatter(path):
        LOGGER.debug(f"Skipping {path} because test_code_snippets is set to false")
        return []

    page_frontmatter = get_page_frontmatter(path)
    is_cookbook = "/cookbook/" in str(path)

    code_blocks = []
    markdown = MarkdownAnalyzer(path)
    mrkdwn_analysis_code_blocks = markdown.identify_code_blocks().get("Code block", [])
    mrkdwn_analysis_code_blocks = [
        convert_jupyter_pip_install_to_bash(code_block)
        for code_block in mrkdwn_analysis_code_blocks
    ]

    for i, mk_code_block in enumerate(mrkdwn_analysis_code_blocks):
        language = _get_code_block_language(mk_code_block["language"])
        start_line = mk_code_block["start_line"]

        if language not in ["bash", "python"]:
            LOGGER.debug(
                f"Skipping code block in {path}:{start_line} because language '{language}' is not supported."
            )
            continue

        if check_skip_code_block(mk_code_block["language"]):
            LOGGER.debug(
                f"Skipping code block in {path}:{start_line} because test is set to false."
            )
            continue

        code_str = _reindent_code_block(mk_code_block["content"])
        if language == "python":
            if (
                page_frontmatter.get("pytest_codeblocks_execute_previous", False)
                or is_cookbook
            ):
                history = [x for x in mrkdwn_analysis_code_blocks[:i]]
            else:
                history = []

            code_blocks.append(
                evaluators.PythonEvaluator(code_str, start_line, history=history)
            )
        elif language == "bash":
            code_blocks.append(
                evaluators.BashEvaluator(code=code_str, start_line=start_line)
            )

    LOGGER.debug(f"Found {len(code_blocks)} code blocks to test in {path}")

    return code_blocks
