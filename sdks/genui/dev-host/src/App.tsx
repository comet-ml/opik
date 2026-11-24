import { useCallback, useMemo, useState } from "react";
import { ViewHost, type HostAdapter } from "opik-genui/ViewHost";
import type { Theme } from "opik-genui/tokens";
import type { Library, View, ViewData, ViewInput } from "opik-genui/view";
import vegaUrl from "opik-genui/libraries/vega.js?url";
import cost from "../fixtures/cost.json";

interface Example {
  view: View;
  data: ViewData;
  theme?: Theme;
}

const EXAMPLE = cost as Example;

const LIBRARY_URLS: Record<Library, string> = { vega: vegaUrl };

const fetchLibrary = (name: Library) =>
  fetch(LIBRARY_URLS[name]).then((r) => r.text());

function loadFromHash(): Example | null {
  const m = /[#&]load=([^&]+)/.exec(window.location.hash);
  return m ? (JSON.parse(decodeURIComponent(m[1])) as Example) : null;
}

function ExampleView({
  example,
  theme,
  onError,
}: {
  example: Example;
  theme: Theme;
  onError: (error: Error) => void;
}) {
  const fetchInput = useCallback(
    (name: string, _input: ViewInput) =>
      name in example.data
        ? Promise.resolve(example.data[name])
        : Promise.reject(new Error(`No data for the input "${name}"`)),
    [example],
  );
  const adapter = useMemo<HostAdapter>(
    () => ({ fetchInput, fetchLibrary, theme, onError }),
    [fetchInput, theme, onError],
  );
  return <ViewHost view={example.view} adapter={adapter} />;
}

export function App() {
  const loaded = useMemo(() => loadFromHash(), []);
  const [text, setText] = useState((loaded ?? EXAMPLE).view.html);
  const [example, setExample] = useState<Example>(loaded ?? EXAMPLE);
  const [theme, setTheme] = useState<Theme>(loaded?.theme ?? "light");
  const [errors, setErrors] = useState<string[]>([]);
  const onError = useCallback(
    (error: Error) => setErrors((list) => [...list, error.message]),
    [],
  );

  return (
    <div className="host" data-theme={theme}>
      <main className="host-main" data-testid="host-main">
        <ExampleView example={example} theme={theme} onError={onError} />
      </main>
      <aside className="host-side">
        <h1>Opik view</h1>
        <p className="muted">
          Inputs {Object.keys(example.view.inputs).join(", ")}. Edit the html
          and click Render.
        </p>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
          data-testid="html-input"
        />
        <div className="host-actions">
          <button
            onClick={() => {
              setErrors([]);
              setExample({ ...example, view: { ...example.view, html: text } });
            }}
            data-testid="render-button"
          >
            Render
          </button>
          <button
            onClick={() => setTheme(theme === "light" ? "dark" : "light")}
            data-testid="theme-button"
          >
            {theme === "light" ? "Dark" : "Light"} theme
          </button>
        </div>
        <ul className="errors" data-testid="errors">
          {errors.map((message, i) => (
            <li key={i}>{message}</li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
