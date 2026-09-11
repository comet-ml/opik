import { useState, useEffect } from "react";

const useIsNetworkOnline = () => {
  const [isNetworkOnline, setIsNetworkOnline] = useState(navigator.onLine);

  useEffect(() => {
    const updateNetworkStatus = () => setIsNetworkOnline(navigator.onLine);

    window.addEventListener("online", updateNetworkStatus);
    window.addEventListener("offline", updateNetworkStatus);

    return () => {
      window.removeEventListener("online", updateNetworkStatus);
      window.removeEventListener("offline", updateNetworkStatus);
    };
  }, []);

  return isNetworkOnline;
};

export default useIsNetworkOnline;
