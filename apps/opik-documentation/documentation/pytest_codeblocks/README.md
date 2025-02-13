# Pytest Codeblocks

The pytest-codeblocks extension allows you to run code blocks from a markdown file using pytest.

This extension was created to ensure that:

1. Each markdown file is tested in isolation using a fresh Python environment - This allows us to detect missing package install statements.
2. It is possible to skip testing certain code blocks.

## How does it work

The pytest-codeblocks extension uses pytest to run the code blocks in a markdown file.

The extension works by:

1. Collecting all the code blocks in a markdown file
2. Creating a new venv for each code block. Currently only Python and Bash code blocks are supported
3. Running each code block in this new env

**Note: This extension only tests that the code successfull runs without raising an error, it does not test the output of the code.**

## Using the pytest-codeblocks

To use the pytest-codeblocks extension, simply run:

```bash
cd apps/opik-documentation/documentation/

pytest
```

## Advanced usage

### Skipping code blocks

The `pytest-codeblocks` extension supports the following features:

- Skipping all code blocks in a file: By setting `pytest_codeblocks_skip: true` in the frontmatter of the markdown file, all code blocks in the file will be skipped.

- Skipping specific code blocks: Setting `{pytest_codeblocks_skip=true}` in the header of the code block will skip it:

  ````
      ```python {pytest_codeblocks_skip=true}

      print("test")

      ```
  ````

- Executing all previous code blocks: By setting `pytest_codeblocks_execute_previous: true` in the frontmatter of the markdown file, all code blocks in the file will be executed before the current code block. This is useful if you have multiple code blocks that depend on variables defined in a previous code block for example.

### Installing default packages

When running the pytest command, you can use the argument `--default-package` to install a package before running the tests. For example, to install a local version of the Opik Python SDK, you can use:

```bash
pytest --default-package=../../../sdks/python
```
