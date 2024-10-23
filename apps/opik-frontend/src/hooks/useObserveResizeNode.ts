import { useCallback, useEffect, useState } from "react";

export function useObserveResizeNode<NodeType = HTMLElement>(
  onChange: (node: NodeType) => void,
) {
  const [node, setNode] = useState<NodeType>();

  const ref = useCallback((element: NodeType) => {
    setNode(element);
  }, []);

  useEffect(() => {
    if (node) {
      const resizeObserver = new ResizeObserver(() => {
        window.requestAnimationFrame(() => {
          if (node) {
            onChange(node);
          }
        });
      });

      resizeObserver.observe(node as never);

      return () => {
        resizeObserver.disconnect();
      };
    }
  }, [node, onChange]);

  return { ref };
}
