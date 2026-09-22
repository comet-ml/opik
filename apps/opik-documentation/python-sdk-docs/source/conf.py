import sys
import os

# Add the source directory to the Python path so Sphinx can find our extensions
sys.path.insert(0, os.path.abspath("."))

# Configuration file for the Sphinx documentation builder.
#
# Full list of options can be found in the Sphinx documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html
# -- Project information -----------------------------------------------------
#

project = "opik"
copyright = "Comet ML"

# -- General configuration ---------------------------------------------------
#

extensions = [
    # Sphinx's own extensions
    "sphinx.ext.autodoc",
    "sphinx.ext.napoleon",
    "sphinx.ext.extlinks",
    "sphinx.ext.intersphinx",
    "sphinx.ext.mathjax",
    "sphinx.ext.todo",
    "sphinx_click.ext",
    # Custom extensions
    "docstring_override",
]

# -- Options for Autodoc --------------------------------------------------------------

autodoc_member_order = "bysource"
autodoc_preserve_defaults = True

# Keep the type hints outside the function signature, moving them to the
# descriptions of the relevant function/methods.
# autodoc_typehints = "description"

# Document all functions, including __init__ and include members
autodoc_default_options = {
    "undoc-members": True,
    "private-members": False,
    "show-inheritance": True,
}

# Mock the heavy third-party integration libraries. autodoc only imports the
# opik.integrations.* wrappers to read their signatures/docstrings; it does not
# need the real SDKs, several of which fail to import in the docs environment
# (e.g. google-genai / litellm raise PydanticSchemaGenerationError at import).
# Without this, every integration reference page renders empty. Do NOT list
# libraries the opik core imports (pydantic, httpx), only integration-only deps.
autodoc_mock_imports = [
    "agents",
    "aisuite",
    "anthropic",
    "boto3",
    "botocore",
    "crewai",
    "crewai_tools",
    "dspy",
    "google",
    "groq",
    "guardrails",
    "harbor",
    "haystack",
    "langchain",
    "langchain_core",
    "langgraph",
    "litellm",
    "llama_index",
    "mistralai",
    "openai",
    "pyagentspec",
    "sagemaker",
]

# -- Options for Markdown files ----------------------------------------------
#

myst_enable_extensions = [
    "colon_fence",
    "deflist",
]
myst_heading_anchors = 3

# -- Options for HTML output -------------------------------------------------
#

html_theme = "furo"
html_title = "opik"
language = "en"

html_static_path = ["_static"]
html_favicon = "_static/favicon.ico"
html_css_files = ["pied-piper-admonition.css"]
