import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import uniqueId from "lodash/uniqueId";
import { useTheme } from "@/contexts/theme-provider";
import { KitPack, loadKit } from "./kit/loadKit";

const CSP =
  "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; require-trusted-types-for 'script'; trusted-types kit";

const RUNTIME = `(() => {
  let data;
  let seq = 0;
  let port;
  const queue = [];
  const handlers = [];
  const post = (message) => (port ? port.postMessage(message) : queue.push(message));
  window.opik = {
    onData(handler) {
      handlers.push(handler);
      if (data !== undefined) handler(data);
    },
    rendered() {
      post({ type: "opik:rendered", seq });
    },
    error(message) {
      post({ type: "opik:error", message: String(message), seq });
    },
    action(name, payload) {
      post({ type: "opik:action", name: String(name), payload });
    },
  };
  window.addEventListener("error", (event) => {
    if (String(event.message).startsWith("ResizeObserver loop")) return;
    window.opik.error(event.message);
  });
  document.addEventListener(
    "click",
    (event) => {
      const link = event.target instanceof Element && event.target.closest("a[href]");
      if (link && !link.getAttribute("href").startsWith("#")) event.preventDefault();
    },
    true,
  );
  window.addEventListener("unhandledrejection", (event) =>
    window.opik.error(event.reason?.message ?? event.reason),
  );
  window.addEventListener("message", function connect(event) {
    if (event.source !== window.parent || event.data?.type !== "opik:connect" || !event.ports[0]) return;
    window.removeEventListener("message", connect);
    port = event.ports[0];
    port.onmessage = (message) => {
      if (message.data?.type === "opik:theme") {
        document.documentElement.dataset.theme = message.data.mode;
      }
      if (message.data?.type === "opik:data") {
        seq = message.data.seq;
        data = message.data.payload;
        handlers.forEach((handler) => handler(data));
      }
    };
    queue.splice(0).forEach((message) => port.postMessage(message));
  });
  const postHeight = () => {
    const bottoms = Array.from(document.body.children, (element) => element.getBoundingClientRect().bottom);
    post({ type: "opik:resize", height: Math.ceil(Math.max(0, ...bottoms) + window.scrollY) });
  };
  const resizeObserver = new ResizeObserver(postHeight);
  document.addEventListener("DOMContentLoaded", () => {
    Array.from(document.body.children).forEach((element) => resizeObserver.observe(element));
    new MutationObserver((records) => {
      records.forEach((record) =>
        record.addedNodes.forEach((node) => node instanceof Element && resizeObserver.observe(node)),
      );
      postHeight();
    }).observe(document.body, { childList: true });
    postHeight();
  });
  window.parent.postMessage({ type: "opik:ready" }, "*");
})();`;

const RENDER_TIMEOUT_MS = 3000;
const MAX_ERROR_LENGTH = 300;
const NOT_RENDERED_ERROR = "The custom view did not render.";
const NAVIGATION_ERROR = "The custom view tried to open another page.";
const MAX_HEIGHT = 20_000;

export type SandboxedViewErrorKind = "error" | "not_rendered";

const buildDocument = (
  html: string,
  kitVersion: number,
  packs: KitPack[],
  themeMode: string,
) => {
  const kit = loadKit(kitVersion, packs);
  return `<!doctype html>
<html data-theme="${themeMode}">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<style>${kit.css}</style>
<script>${RUNTIME}</script>
<script>${kit.js}</script>
</head>
<body>${html}</body>
</html>`;
};

type SandboxedViewProps = {
  html: string;
  data: unknown;
  kitVersion: number;
  packs: KitPack[];
  title: string;
  actions?: string[];
  onAction?: (name: string, payload: unknown) => void;
  onError?: (message: string, kind: SandboxedViewErrorKind) => void;
};

const SandboxedView: React.FC<SandboxedViewProps> = ({
  html,
  data,
  kitVersion,
  packs,
  title,
  actions,
  onAction,
  onError,
}) => {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const portRef = useRef<MessagePort>();
  const renderTimeoutRef = useRef<ReturnType<typeof setTimeout>>();
  const dataSeqRef = useRef(0);
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const onActionRef = useRef(onAction);
  onActionRef.current = onAction;
  const actionsRef = useRef(actions);
  actionsRef.current = actions;
  const dataRef = useRef(data);
  dataRef.current = data;
  const [height, setHeight] = useState(0);
  const { themeMode } = useTheme();
  const themeModeRef = useRef(themeMode);
  themeModeRef.current = themeMode;

  const { srcDoc, docKey } = useMemo(
    () => ({
      srcDoc: buildDocument(html, kitVersion, packs, themeModeRef.current),
      docKey: uniqueId("sandboxed-view-"),
    }),
    [html, kitVersion, packs],
  );
  const loadCountRef = useRef(0);
  const [navigatedDocKey, setNavigatedDocKey] = useState<string>();

  const fail = useCallback((message: string, kind: SandboxedViewErrorKind) => {
    clearTimeout(renderTimeoutRef.current);
    onErrorRef.current?.(
      message.slice(0, MAX_ERROR_LENGTH) || NOT_RENDERED_ERROR,
      kind,
    );
  }, []);

  const sendData = useCallback(() => {
    dataSeqRef.current += 1;
    portRef.current?.postMessage({
      type: "opik:data",
      payload: dataRef.current,
      seq: dataSeqRef.current,
    });
    clearTimeout(renderTimeoutRef.current);
    renderTimeoutRef.current = setTimeout(
      () => fail(NOT_RENDERED_ERROR, "not_rendered"),
      RENDER_TIMEOUT_MS,
    );
  }, [fail]);

  useEffect(() => {
    const handlePortMessage = (event: MessageEvent) => {
      const seq = event.data?.seq;
      if (typeof seq === "number" && seq !== 0 && seq !== dataSeqRef.current) {
        return;
      }
      if (event.data?.type === "opik:rendered") {
        clearTimeout(renderTimeoutRef.current);
      }
      if (event.data?.type === "opik:error") {
        fail(String(event.data.message), "error");
      }
      if (
        event.data?.type === "opik:action" &&
        actionsRef.current?.includes(event.data.name)
      ) {
        onActionRef.current?.(event.data.name, event.data.payload);
      }
      if (event.data?.type === "opik:resize") {
        const nextHeight = Number(event.data.height);
        if (Number.isFinite(nextHeight)) {
          setHeight(Math.min(Math.max(nextHeight, 0), MAX_HEIGHT));
        }
      }
    };
    const handleMessage = (event: MessageEvent) => {
      const frame = iframeRef.current?.contentWindow;
      if (
        !frame ||
        event.source !== frame ||
        event.data?.type !== "opik:ready" ||
        portRef.current
      ) {
        return;
      }
      const channel = new MessageChannel();
      portRef.current = channel.port1;
      channel.port1.onmessage = handlePortMessage;
      frame.postMessage({ type: "opik:connect" }, "*", [channel.port2]);
      channel.port1.postMessage({
        type: "opik:theme",
        mode: themeModeRef.current,
      });
      sendData();
    };
    loadCountRef.current = 0;
    window.addEventListener("message", handleMessage);
    return () => {
      window.removeEventListener("message", handleMessage);
      portRef.current?.close();
      portRef.current = undefined;
      clearTimeout(renderTimeoutRef.current);
    };
  }, [srcDoc, sendData, fail]);

  const isNavigationBlocked = navigatedDocKey === docKey;

  useEffect(() => {
    if (isNavigationBlocked) {
      fail(NAVIGATION_ERROR, "error");
      return;
    }
    sendData();
  }, [data, sendData, isNavigationBlocked, fail]);

  useEffect(() => {
    portRef.current?.postMessage({ type: "opik:theme", mode: themeMode });
  }, [themeMode]);

  const handleLoad = useCallback(() => {
    loadCountRef.current += 1;
    if (loadCountRef.current < 2) return;
    portRef.current?.close();
    portRef.current = undefined;
    setNavigatedDocKey(docKey);
  }, [docKey]);

  if (isNavigationBlocked) {
    return <p className="comet-body-xs text-muted-slate">{NAVIGATION_ERROR}</p>;
  }

  return (
    <iframe
      key={docKey}
      ref={iframeRef}
      onLoad={handleLoad}
      title={title}
      sandbox="allow-scripts"
      srcDoc={srcDoc}
      className="block w-full border-0"
      style={{ height }}
    />
  );
};

export default SandboxedView;
