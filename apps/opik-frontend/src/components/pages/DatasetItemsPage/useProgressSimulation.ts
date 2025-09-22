import { useState, useEffect, useCallback } from "react";

const useProgressSimulation = (isPending: boolean) => {
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!isPending) {
      setProgress(0);
      setMessage("");
      return;
    }

    setProgress(0);
    setMessage("Initializing AI generation...");

    const interval = setInterval(() => {
      setProgress((prev) => {
        const next = prev + Math.random() * 15;
        if (next > 90) return 90;

        if (next > 20 && next <= 40) {
          setMessage("Analyzing dataset patterns...");
        } else if (next > 40 && next <= 70) {
          setMessage("Generating synthetic samples...");
        } else if (next > 70) {
          setMessage("Finalizing generated data...");
        }

        return next;
      });
    }, 800);

    return () => clearInterval(interval);
  }, [isPending]);

  const complete = useCallback(() => {
    setProgress(100);
    setMessage("Generation completed successfully!");
  }, []);

  return { progress, message, complete };
};

export default useProgressSimulation;
