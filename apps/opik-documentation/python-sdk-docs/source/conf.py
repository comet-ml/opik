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
