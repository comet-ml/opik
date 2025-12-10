import { useQueryParam, BooleanParam } from "use-query-params";
import { useCallback } from "react";

const QUERY_PARAM_KEY = "create_experiment";

export const useOpenCreateExperimentDialog = () => {
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
