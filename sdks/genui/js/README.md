# opik-genui

The engine behind [Opik](https://github.com/comet-ml/opik) views. A view is HTML, CSS and script written by a model; it carries no data and declares inputs that the host resolves. This package renders a view in a sandboxed iframe, the same way in every host.

```
npm install opik-genui
```

React 19 is a peer dependency. The version of this package is the Opik release it shipped with.

## Usage

```tsx
import { useCallback, useMemo } from "react";
import { ViewHost, type HostAdapter } from "opik-genui/ViewHost";
import type { Library, View, ViewInput } from "opik-genui/view";
import "opik-genui/styles.css";

function SavedView({ view }: { view: View }) {
  const fetchInput = useCallback(
    (name: string, input: ViewInput) => loadRows(input.query_id),
    [],
  );
  const fetchLibrary = useCallback(
    (name: Library) => fetch(`/assets/genui/${name}.js`).then((r) => r.text()),
    [],
  );
  const adapter = useMemo<HostAdapter>(
    () => ({ fetchInput, fetchLibrary, theme: "light" }),
    [fetchInput, fetchLibrary],
  );
  return <ViewHost view={view} adapter={adapter} />;
}
```

Keep `fetchInput` and `fetchLibrary` stable: a new function identity means fetch again.

## Entry points

| Import | What it is |
|---|---|
| `opik-genui/ViewHost` | The host component. It refuses a view it cannot render, resolves the inputs, loads the declared libraries and mounts the frame. |
| `opik-genui/HtmlFrame` | The iframe host on its own, for a caller that already has the data. |
| `opik-genui/contract` | The HTML contract per `engine_version` and `ENGINE_VERSION`, the latest one. |
| `opik-genui/tokens` | The `--ov-` token values for light and dark. |
| `opik-genui/view` | The view and input types, the areas, the libraries and the size caps. |
| `opik-genui/styles.css` | The host-side stylesheet. |
| `opik-genui/libraries/vega.js` | Vega, Vega-Lite and Vega-Embed as one script, for a view that declares the `vega` library. Serve the file from your own origin and return its text from `fetchLibrary`. |

## Requirements for the host page

- Limit `frame-src` (or `child-src`) in the page's Content Security Policy to your own origins. A view that navigates itself is stopped by the host page, not by the view's sandbox.
- A library bundle contains third-party code. Its licences are in `opik-genui/libraries/<name>.licenses.txt`; ship that file with the bundle.

## Licence

Apache-2.0. Authoring guidance for the model that writes the views is in the Python package of the same name.
