import { useEffect } from "react";

const useSegment = (username?: string) => {
  useEffect(() => {
    if (window.analytics && username) {
      window.analytics.identify(username);
    }
  }, [username]);
};

export default useSegment;
