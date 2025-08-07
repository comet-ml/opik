"""
Custom Sphinx extension to remove Examples sections from docstrings.
"""


def override_docstring(app, what, name, obj, options, lines):
    """
    Remove Examples sections from method docstrings in opik.rest_api.

    Args:
        app: Sphinx application object
        what: Type of object being documented
        name: Full name of the object
        obj: The actual object being documented
        options: Options passed to the directive
        lines: List of lines in the original docstring
    """
    if what == "method" and "opik.rest_api" in name:
        # Remove everything after ".. rubric:: Examples"
        for i, line in enumerate(lines):
            if ".. rubric:: Examples" in line:
                lines[:] = lines[:i]
                break


def setup(app):
    """
    Setup function for the Sphinx extension.

    This registers the docstring override function with Sphinx's autodoc extension.

    Args:
        app: Sphinx application object
    """
    # Connect our override function to the autodoc-process-docstring event
    app.connect("autodoc-process-docstring", override_docstring)

    return {
        "version": "1.0",
        "parallel_read_safe": True,
        "parallel_write_safe": True,
    }
