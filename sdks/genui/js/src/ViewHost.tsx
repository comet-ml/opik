import { useEffect, useRef, useState, type CSSProperties } from "react";
import { CONTRACTS, unknownVersionMessage } from "./contract.js";
import { HtmlFrame } from "./HtmlFrame.js";
import { TOKENS, type Theme } from "./tokens.js";
import {
  INPUT_KINDS,
  LIBRARIES,
  type Library,
  type View,
  type ViewData,
  type ViewInput,
} from "./view.js";

export interface HostAdapter {
  fetchInput: (name: string, input: ViewInput) => Promise<unknown>;
  fetchLibrary?: (name: Library) => Promise<string>;
  theme: Theme;
  onError?: (error: Error) => void;
}

export interface ViewHostProps {
  view: View;
  adapter: HostAdapter;
}

type Load =
  | { status: "loading" }
  | { status: "ready"; view: View; data: ViewData; libraries: string[] }
  | { status: "failed"; error: Error };

const asError = (e: unknown) => (e instanceof Error ? e : new Error(String(e)));

export function unsupportedReason(view: View): string | null {
  if (view.type !== "html")
    return `The view type "${String(view.type)}" is not supported.`;
  if (!CONTRACTS.has(view.engine_version))
    return unknownVersionMessage(view.engine_version);
  const kind = Object.values(view.inputs)
    .map((input) => String(input.kind))
    .find((k) => !INPUT_KINDS.includes(k));
  if (kind) return `The input kind "${kind}" is not supported.`;
  const library = (view.libraries ?? []).find(
    (name) => !LIBRARIES.includes(name),
  );
  return library ? `The library "${String(library)}" is not supported.` : null;
}

export function ViewHost({ view, adapter }: ViewHostProps) {
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const onError = useRef(adapter.onError);
  onError.current = adapter.onError;
  const { fetchInput, fetchLibrary, theme } = adapter;
  const libraryCache = useRef(new Map<Library, Promise<string>>());

  useEffect(() => {
    libraryCache.current = new Map();
  }, [fetchLibrary]);

  useEffect(() => {
    let cancelled = false;
    const fail = (error: Error) => {
      if (cancelled) return;
      onError.current?.(error);
      setLoad({ status: "failed", error });
    };
    const reason = unsupportedReason(view);
    if (reason) {
      fail(new Error(reason));
      return;
    }
    setLoad((prev) =>
      prev.status === "ready" && prev.view === view
        ? prev
        : { status: "loading" },
    );
    Promise.all([
      Promise.all(
        Object.entries(view.inputs).map(([name, input]) =>
          fetchInput(name, input).then((value) => [name, value] as const),
        ),
      ),
      Promise.all(
        (view.libraries ?? []).map((name) => {
          if (!fetchLibrary)
            return Promise.reject(new Error("This host loads no libraries."));
          const cache = libraryCache.current;
          const cached = cache.get(name);
          if (cached) return cached;
          const code = fetchLibrary(name);
          cache.set(name, code);
          code.catch(() => cache.delete(name));
          return code;
        }),
      ),
    ]).then(
      ([pairs, libraries]) => {
        if (!cancelled)
          setLoad({
            status: "ready",
            view,
            data: Object.fromEntries(pairs),
            libraries,
          });
      },
      (e) => fail(asError(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [view, fetchInput, fetchLibrary]);

  return (
    <div
      className="ov-view"
      data-theme={theme}
      style={TOKENS[theme] as CSSProperties}
    >
      {load.status === "ready" && load.view === view ? (
        <HtmlFrame
          html={view.html}
          engineVersion={view.engine_version}
          libraries={load.libraries}
          data={load.data}
          theme={theme}
          onError={(error) => onError.current?.(error)}
        />
      ) : load.status === "failed" ? (
        <div className="ov-error">{load.error.message}</div>
      ) : (
        <div className="ov-placeholder">Loading view data</div>
      )}
    </div>
  );
}
