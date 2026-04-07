import { useQueryParam, BooleanParam } from "use-query-params";
import { useCallback } from "react";

type LayoutDialogKey = "quickstart" | "feedback";

export const useLayoutDialog = (key: LayoutDialogKey) => {
  const [isOpen = false, setIsOpen] = useQueryParam(key, BooleanParam, {
    updateType: "replaceIn",
  });

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
