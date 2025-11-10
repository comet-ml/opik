import { useQueryParam, BooleanParam } from "use-query-params";
import { useCallback } from "react";

const QUERY_PARAM_KEY = "quickstart";

export const useOpenQuickStartDialog = () => {
  const [isOpen = false, setIsOpen] = useQueryParam(
    QUERY_PARAM_KEY,
    BooleanParam,
    {
      updateType: "replaceIn",
    },
  );

  const open = useCallback(() => {
    setIsOpen(true);
  }, [setIsOpen]);

  const close = useCallback(() => {
    setIsOpen(false);
  }, [setIsOpen]);

  return {
    isOpen: Boolean(isOpen),
    open,
    close,
    setOpen: setIsOpen,
  };
};
