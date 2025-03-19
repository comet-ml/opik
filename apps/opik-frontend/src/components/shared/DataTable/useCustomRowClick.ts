import React, { useCallback, useRef } from "react";
import isFunction from "lodash/isFunction";

const CLICK_RESET_TIMEOUT = 200;

type UseCustomRowClickParams<TData> = {
  onRowClick?: (row: TData) => void;
};

type UseCustomRowClickResponse<TData> = {
  onClick?: (event: React.MouseEvent<HTMLElement>, data: TData) => void;
};

const useCustomRowClick = <TData>({
  onRowClick,
}: UseCustomRowClickParams<TData>): UseCustomRowClickResponse<TData> => {
  const callbackTimeout = useRef<NodeJS.Timeout>();

  const onClickHandler = useCallback(
    (event: React.MouseEvent<HTMLElement>, data: TData) => {
      if (callbackTimeout.current) {
        clearTimeout(callbackTimeout.current);
      }

      const selection = window.getSelection();
      const hasSelection =
        selection &&
        selection.toString().length > 0 &&
        selection.containsNode(event.target as Node, true);

      if (isFunction(onRowClick) && data && !hasSelection) {
        callbackTimeout.current = setTimeout(() => {
          onRowClick(data);
        }, CLICK_RESET_TIMEOUT);
      }
    },
    [onRowClick],
  );

  return isFunction(onRowClick)
    ? {
        onClick: onClickHandler,
      }
    : {};
};

export default useCustomRowClick;
