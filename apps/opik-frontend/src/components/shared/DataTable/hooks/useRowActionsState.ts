import { useCallback, useState } from "react";

export const useRowActionsState = () => {
  const [dialogOpen, setDialogOpen] = useState<string>("");

  const open = useCallback((dialogId: string) => {
    return () => {
      setDialogOpen(dialogId);
    };
  }, []);

  const close = useCallback(() => {
    setDialogOpen("");
  }, []);

  return {
    dialogOpen,
    open,
    close,
  };
};
