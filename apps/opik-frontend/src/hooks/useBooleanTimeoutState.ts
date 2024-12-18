import { useEffect, useState } from "react";

const DEFAULT_TIMEOUT = 3000;

type UseBooleanTimeoutStateParams = {
  state?: boolean;
  timeout?: number;
};

export const useBooleanTimeoutState = ({
  state = false,
  timeout = DEFAULT_TIMEOUT,
}: UseBooleanTimeoutStateParams) => {
  const stateObject = useState<boolean>(state);
  const [internalState, setInternalState] = stateObject;

  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (internalState) {
      timer = setTimeout(() => setInternalState(false), timeout);
    }
    return () => {
      clearTimeout(timer);
    };
  }, [timeout, internalState, setInternalState]);

  return stateObject;
};
