# opik-genui

Authoring data for [Opik](https://github.com/comet-ml/opik) views. A view is HTML, CSS and script written by a model and rendered in a sandboxed iframe by the `opik-genui` npm package. This package gives a Python author the prompt to write a view and the constants to check it against.

```
pip install opik-genui
```

It has no dependencies. The version of this package is the Opik release it shipped with, and it always matches the npm package of the same version.

## Usage

```python
import opik_genui

system_prompt = opik_genui.prompt("widget")

view = {
    "type": "html",
    "engine_version": opik_genui.ENGINE_VERSION,
    "html": html,
    "inputs": {"rows": {"kind": "query", "query_id": query_id}},
    "libraries": ["vega"],
}
assert len(html) <= opik_genui.MAX_HTML_LENGTH
```

| Name | What it is |
|---|---|
| `prompt(area)` | The authoring prompt for an area. |
| `AREAS` | The places a view can be shown. |
| `LIBRARIES` | The libraries a view may declare. |
| `ENGINE_VERSION` | The HTML contract version a view written with this prompt targets. |
| `MAX_HTML_LENGTH` | The size cap the Opik backend enforces on save. |
| `TOKENS` | The `--ov-` token values for light and dark. |
| `__version__` | The package version. |

## Licence

Apache-2.0.
