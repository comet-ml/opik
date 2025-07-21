import { useCallback, useRef, useState } from "react";

type ConfirmCallback = () => void;

export function useConfirmAction() {
  const [isOpen, setIsOpen] = useState(false);
  const callbackRef = useRef<ConfirmCallback | null>(null);

  const requestConfirm = useCallback((onConfirm: ConfirmCallback) => {
    callbackRef.current = onConfirm;
    setIsOpen(true);
  }, []);

  const confirm = useCallback(() => {
    if (callbackRef.current) {
      callbackRef.current();
    }
    callbackRef.current = null;
    setIsOpen(false);
  }, []);

  const cancel = useCallback(() => {
    callbackRef.current = null;
    setIsOpen(false);
  }, []);

  return {
    isOpen,
    setIsOpen,
    requestConfirm,
    confirm,
    cancel,
  } as const;
}
