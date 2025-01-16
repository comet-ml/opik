# Opik Python SDK

For a more general contribution guide (backend + frontend + SDK) see our root [Contribution guide](../../CONTRIBUTING.md).

# Coding guidelines
This guide is still in progress, however, it already contains useful information that you should know before submitting your PR.

## General
We care a lot about the code maintainability. Well-organized logic which is easy to extend, re-factor and, most importantly - **read**, is what we are striving for.
1. Follow [SOLID](https://realpython.com/solid-principles-python/) principles. Pay special attention to the "Single Responsibility" one.
2. Avoid large modules, large classes, and large functions. Separate the code properly and describe this separation with names, not with comments. (See [1])
3. If the name is not used outside of the class/module - it should be `_protected`.
4. Don't violate the access rules! We know that Python allows you to access _protected/__private variables, but in Opik we are quite strict about not abusing that, whether it's an internal code or a test (don't forget about [3]!).
5. Use comments only for something non-trivial that is hard to describe in any other way. Apart from these cases, comments should be used to answer the question "Why?" not "What?".

## Imports
1. Import module - not name.
    Instead of this:
    ```python
    from threading import Thread  # bad!
    thread = Thread()
    ```
    do this:
    ```python
    import threading  # good!
    thread = threading.Thread
    ```

2. If the import statement is too big, you can do the following
    ```python
    from opik.rest_api.core import error as rest_api_error  # ok!
    ```

3. If you are working in the namespace, you likely don't need to keep most of the parent namespaces
    ```python
    # inside opik.api_objects.dataset
    from . import dataset_item  # ok!
    ```

4. Of course, there might be exceptions from this rule, for example, some common types can be imported as is.
    ```python
    from typing import Dict, List  # ok!
    from opik.types import FeedbackScoreDict  # ok!
    ```

## Naming
1. Avoid abbreviations. In the vast majority of cases, it is not a problem to use variable names. People spend more time understanding what "fs" means than reading the word "files" or "file_system".
   ```python
   for d in dataset_items:  # bad!

   for item in dataset_items:  # ok!
       ...
   for dataset_item in dataset_items  # ok!
       ...
   ```
2. Avoid creating modules like `utils.py`, `helpers.py`, `misc.py` etc. Especially in the big namespaces. They can quickly become dumps where people put everything that they haven't been able to create a better place for in 10 seconds after they started thinking about it. You can create those files though, but they should be localized in their namespaces designed for some specific features. In vast majority of cases there are better module names.

## Testing
We highly encourage writing tests and we develop a lot of features in a test-driven way.
1. Test public API, don't violate privacy.
2. If you are an external contributor - make sure that the unit tests and e2e tests are green (they can be executed anywhere because they don't require any API keys or permissions). For internal Opik developers everything should be green in the CI.
3. If you have `if-statements` in your code or some non-trivial boiler-plate code - it's probably a reason to think about add some unit tests for that. The more complex your code, the higher chance you'll be asked to provide unit tests for it.
4. If you are introducing a new feature that includes communication with the backend - it's better to add some e2e tests for that (at least the happy flow one).
5. Avoid testing with e2e tests something that can be tested with unit tests. E2E tests are time-consuming.
6. If you are introducing a change in one of the integrations (or a new integration), make sure the integration tests are working. They usually require API keys configured for the services the integration works with. When the external contributor opens a PR, their tests will not use our Github secrets so consider providing your repo with an API key required for the integration. In that case, we will see that the tests are green.
7. We are using `fake_backend` fixture together with a special Opik assertions DSL(domain-specific language) for a lot of unit tests and library integration tests. We encourage you to use it as well! There is plenty of examples, you can take a look at `tests/unit/decorator/test_tracker_outputs.py` or `tests/library_integration/openai/test_openai.py`. It provides a pretty simple API for specifying the traces content you expect your feature to log.
