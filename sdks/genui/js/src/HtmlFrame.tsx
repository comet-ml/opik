import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type {
  McpUiHostContext,
  McpUiHostContextChangedNotification,
  McpUiInitializeRequest,
  McpUiInitializeResult,
  McpUiInitializedNotification,
  McpUiResourceTeardownRequest,
  McpUiSizeChangedNotification,
  McpUiStyles,
  McpUiToolInputNotification,
  McpUiToolResultNotification,
} from "@modelcontextprotocol/ext-apps";
import {
  ENGINE_VERSION,
  PROTOCOL_VERSION,
  buildViewDocument,
  unknownVersionMessage,
} from "./contract";
import { TOKENS, type Theme } from "./tokens";
import { MAX_FRAME_HEIGHT, type ViewData } from "./view";

export interface HtmlFrameProps {
  html: string;
  engineVersion: number;
  libraries?: readonly string[];
  data: ViewData;
  theme: Theme;
  initialHeight?: number;
  maxHeight?: number;
  onError?: (error: Error) => void;
}

const STOPPED = "The view tried to leave its sandbox and was stopped.";
const MAX_REPORTED_ERRORS = 20;
const RESIZE_INTERVAL_MS = 250;

const useClientLayoutEffect =
  typeof window === "undefined" ? useEffect : useLayoutEffect;

type LogNotification = {
  method: "notifications/message";
  params: { level: string; data: unknown };
};
type Notification =
  | McpUiInitializedNotification
  | McpUiSizeChangedNotification
  | LogNotification;
type JsonRpcId = number | string;
type ViewMessage =
  | ({ jsonrpc: "2.0"; id: JsonRpcId } & McpUiInitializeRequest)
  | ({ jsonrpc: "2.0"; id?: undefined } & Notification)
  | { jsonrpc: "2.0"; id: JsonRpcId; method: string };
type HostMessage =
  | { jsonrpc: "2.0"; id: JsonRpcId; result: McpUiInitializeResult }
  | { jsonrpc: "2.0"; id: JsonRpcId; error: { code: number; message: string } }
  | ({ jsonrpc: "2.0"; id: JsonRpcId } & McpUiResourceTeardownRequest)
  | ({ jsonrpc: "2.0" } & (
      | McpUiToolInputNotification
      | McpUiToolResultNotification
      | McpUiHostContextChangedNotification
    ));

const hostContext = (theme: Theme): McpUiHostContext => ({
  theme,
  styles: { variables: TOKENS[theme] as unknown as McpUiStyles },
});

export function HtmlFrame({
  html,
  engineVersion,
  libraries,
  data,
  theme,
  initialHeight = 120,
  maxHeight = MAX_FRAME_HEIGHT,
  onError,
}: HtmlFrameProps) {
  const ref = useRef<HTMLIFrameElement>(null);
  const [frameHeight, setFrameHeight] = useState(initialHeight);
  const [loads, setLoads] = useState(0);
  const [initialized, setInitialized] = useState(0);
  const contextTheme = useRef<Theme | null>(null);
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const srcDoc = useMemo(
    () => buildViewDocument(engineVersion, html, libraries),
    [engineVersion, html, libraries],
  );
  const [navigatedFrom, setNavigatedFrom] = useState<string | null>(null);
  const mounted = srcDoc !== null;
  const stopped = mounted && navigatedFrom === srcDoc;
  const expected = useRef({
    doc: "",
    loads: 0,
    answered: false,
    handshake: false,
  });
  const reported = useRef(new Set<string>());
  const resize = useRef({ height: 0, timer: 0 });

  const stop = () => {
    expected.current = { doc: "", loads: 0, answered: false, handshake: false };
    setInitialized(0);
    setNavigatedFrom(ref.current?.srcdoc ?? null);
  };

  const onLoad = () => {
    setLoads((n) => n + 1);
    if (expected.current.loads > 0) expected.current.loads -= 1;
    else stop();
  };

  useClientLayoutEffect(() => {
    const current = expected.current;
    if (!mounted) {
      expected.current = {
        doc: "",
        loads: 0,
        answered: false,
        handshake: false,
      };
      return;
    }
    if (stopped || current.doc === srcDoc) return;
    reported.current = new Set();
    expected.current = {
      doc: srcDoc,
      loads: current.doc ? current.loads + 1 : 1,
      answered: false,
      handshake: false,
    };
  }, [srcDoc, stopped]);

  useEffect(() => {
    const frame = ref.current;
    if (!frame) return;
    const post = (msg: HostMessage) =>
      frame.contentWindow?.postMessage(msg, "*");
    const onMessage = (e: MessageEvent) => {
      if (
        e.source !== frame.contentWindow ||
        !e.data ||
        typeof e.data !== "object"
      )
        return;
      const msg = e.data as Partial<ViewMessage>;
      if (msg.jsonrpc !== "2.0" || typeof msg.method !== "string") return;
      if (msg.method === "ui/initialize" && msg.id != null) {
        if (expected.current.handshake) return stop();
        expected.current.answered = true;
        setInitialized(0);
        contextTheme.current = theme;
        post({
          jsonrpc: "2.0",
          id: msg.id,
          result: {
            protocolVersion: PROTOCOL_VERSION,
            hostInfo: { name: "opik-genui", version: String(ENGINE_VERSION) },
            hostCapabilities: {},
            hostContext: hostContext(theme),
          },
        });
      } else if (msg.method === "ui/notifications/initialized") {
        if (!expected.current.answered) return;
        expected.current.handshake = true;
        post({
          jsonrpc: "2.0",
          method: "ui/notifications/tool-input",
          params: { arguments: {} },
        });
        setInitialized((n) => n + 1);
      } else if (msg.method === "ui/notifications/size-changed") {
        const h = (msg as McpUiSizeChangedNotification).params?.height;
        if (h === undefined || !Number.isFinite(h)) return;
        const pending = resize.current;
        pending.height = Math.min(maxHeight, h);
        if (pending.timer) return;
        setFrameHeight(pending.height);
        pending.timer = window.setTimeout(() => {
          pending.timer = 0;
          setFrameHeight(pending.height);
        }, RESIZE_INTERVAL_MS);
      } else if (msg.method === "notifications/message") {
        const text = String((msg as LogNotification).params?.data);
        if (
          reported.current.has(text) ||
          reported.current.size >= MAX_REPORTED_ERRORS
        )
          return;
        reported.current.add(text);
        onErrorRef.current?.(new Error(text));
      } else if (msg.id != null) {
        post({
          jsonrpc: "2.0",
          id: msg.id,
          error: { code: -32601, message: "Method not found" },
        });
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [theme, stopped, maxHeight, mounted]);

  useEffect(() => {
    const pending = resize.current;
    return () => window.clearTimeout(pending.timer);
  }, []);

  useClientLayoutEffect(() => {
    const frame = ref.current;
    return () => {
      const msg: HostMessage = {
        jsonrpc: "2.0",
        id: "teardown",
        method: "ui/resource-teardown",
        params: {},
      };
      frame?.contentWindow?.postMessage(msg, "*");
    };
  }, []);

  useEffect(() => {
    const frame = ref.current;
    if (!frame || !initialized) return;
    const msg: HostMessage = {
      jsonrpc: "2.0",
      method: "ui/notifications/tool-result",
      params: { content: [], structuredContent: { data } },
    };
    frame.contentWindow?.postMessage(msg, "*");
  }, [data, initialized]);

  useEffect(() => {
    const frame = ref.current;
    if (!frame || !initialized || contextTheme.current === theme) return;
    contextTheme.current = theme;
    const handle = requestAnimationFrame(() => {
      const msg: HostMessage = {
        jsonrpc: "2.0",
        method: "ui/notifications/host-context-changed",
        params: hostContext(theme),
      };
      frame.contentWindow?.postMessage(msg, "*");
    });
    return () => cancelAnimationFrame(handle);
  }, [theme, initialized]);

  useEffect(() => {
    if (!mounted)
      onErrorRef.current?.(new Error(unknownVersionMessage(engineVersion)));
    else if (stopped) onErrorRef.current?.(new Error(STOPPED));
  }, [mounted, stopped, engineVersion]);

  if (!mounted)
    return (
      <div className="ov-error">{unknownVersionMessage(engineVersion)}</div>
    );

  if (stopped) return <div className="ov-error">{STOPPED}</div>;

  return (
    <iframe
      ref={ref}
      className="ov-frame"
      title="view"
      sandbox="allow-scripts"
      srcDoc={srcDoc}
      onLoad={onLoad}
      style={{ height: frameHeight }}
      data-view-loads={loads}
      data-view-initialized={initialized}
    />
  );
}
