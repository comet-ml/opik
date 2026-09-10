import { useCallback, useEffect, useState } from "react";

export function useObserveResizeNode<NodeType = HTMLElement>(
  onChange: (node: NodeType) => void,
  observeBody = false,
) {
  const [node, setNode] = useState<NodeType>();

  const ref = useCallback((element: NodeType) => {
    setNode(element);
  }, []);

  useEffect(() => {
    if (node) {
      const resizeObserver = new ResizeObserver(() => {
        window.requestAnimationFrame(() => {
          // By the time the frame runs the node may have been unmounted, and a
          // detached node measures 0. Reporting that overwrites a good value
          // with a wrong one.
          if (node && (node as unknown as Element).isConnected !== false) {
            onChange(node);
          }
        });
      });

      resizeObserver.observe(node as never);
      if (observeBody) {
        resizeObserver.observe(window.document.body as never);
      }

      return () => {
        resizeObserver.disconnect();
      };
    }
  }, [node, observeBody, onChange]);

  return { ref, node };
}
