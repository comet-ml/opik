import React, { useCallback, useRef } from "react";

const DEFAULT_CLICK_DELAY_MS = 200;

type UseCustomRowClickResponse = (
  event: React.MouseEvent<HTMLElement>,
  onClick: () => void,
) => void;

type UseCustomRowClickOptions = {
  delayMs?: number;
};

const useCustomRowClick = (
  options: UseCustomRowClickOptions = {},
): UseCustomRowClickResponse => {
  const { delayMs = DEFAULT_CLICK_DELAY_MS } = options;
  const callbackTimeout = useRef<ReturnType<typeof setTimeout>>();

  return useCallback(
    (event: React.MouseEvent<HTMLElement>, onClick: () => void) => {
      if (callbackTimeout.current) {
        clearTimeout(callbackTimeout.current);
      }

      const selection = window.getSelection();
      const hasSelection =
        selection &&
        selection.toString().length > 0 &&
        selection.containsNode(event.target as Node, true);

      if (!hasSelection) {
        callbackTimeout.current = setTimeout(() => {
          onClick();
        }, delayMs);
      }
    },
    [delayMs],
  );
};

export default useCustomRowClick;
