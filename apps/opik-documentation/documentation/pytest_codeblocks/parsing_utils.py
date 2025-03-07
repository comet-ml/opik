from mrkdwn_analysis import MarkdownAnalyzer
import logging
from . import evaluators
from typing import List, Union

LOGGER = logging.getLogger(__name__)


def _get_code_block_language(language: str):
    """
    This method extracts the language of the code block based on the string that is
    after ``` in each code block.
    """
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
                    k, v = line.split(":")
                    v = v.strip()
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


def get_code_blocs(
    path: str,
) -> List[Union[evaluators.PythonEvaluator, evaluators.BashEvaluator]]:
    LOGGER.debug(f"Finding code blocks in {path}")

    if check_skip_frontmatter(path):
        LOGGER.debug(f"Skipping {path} because test_code_snippets is set to false")
        return []

    page_frontmatter = get_page_frontmatter(path)
    is_cookbook = "/cookbook/" in path

    code_blocks = []
    markdown = MarkdownAnalyzer(path)
    mrkdwn_analysis_code_blocks = markdown.identify_code_blocks().get("Code block", [])
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
                history = [x["content"] for x in mrkdwn_analysis_code_blocks[:i]]
            else:
                history = []

            if code_str[0:12] == "%pip install":
                code_blocks.append(
                    evaluators.BashEvaluator(code=code_str[1:], start_line=start_line)
                )
            else:
                code_blocks.append(
                    evaluators.PythonEvaluator(code_str, start_line, history=history)
                )
        elif language == "bash":
            code_blocks.append(
                evaluators.BashEvaluator(code=code_str, start_line=start_line)
            )

    LOGGER.debug(f"Found {len(code_blocks)} code blocks to test in {path}")

    return code_blocks
