import { useState, useEffect, useCallback } from "react";

interface UseProgressSimulationOptions {
  /**
   * Array of messages to cycle through during progress simulation
   */
  messages: string[];
  /**
   * Whether the progress simulation is active
   */
  isPending: boolean;
  /**
   * Interval in milliseconds for progress updates (default: 800ms)
   */
  intervalMs?: number;
  /**
   * Whether to loop the progress animation continuously (default: false)
   */
  loop?: boolean;
}

interface UseProgressSimulationReturn {
  /**
   * Current progress percentage (0-100)
   */
  progress: number;
  /**
   * Current progress message
   */
  message: string;
  /**
   * Function to mark progress as complete
   */
  complete: () => void;
}

/**
 * Hook to simulate progress with percentage and rotating messages
 * @param options - Configuration options
 * @returns Progress state and completion callback
 */
const useProgressSimulation = ({
  messages,
  isPending,
  intervalMs = 800,
  loop = false,
}: UseProgressSimulationOptions): UseProgressSimulationReturn => {
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    setProgress(0);
    if (!isPending) return;

    const interval = setInterval(() => {
      setProgress((prev) => {
        const next = prev + Math.random() * 15;

        if (loop) {
          // In loop mode, reset to 0 when reaching 100
          return next >= 100 ? 0 : next;
        }

        // In normal mode, cap at 90
        return Math.min(next, 90);
      });
    }, intervalMs);

    return () => clearInterval(interval);
  }, [isPending, intervalMs, loop]);

  const complete = useCallback(() => setProgress(100), []);

  // Derived rather than set alongside progress, so the progress updater stays free of side effects.
  let message = "";
  if (progress >= 100) {
    message = messages[messages.length - 1] || "Completed successfully!";
  } else if (isPending) {
    const maxProgress = loop ? 100 : 90;
    const messageIndex = Math.min(
      Math.floor((progress / maxProgress) * messages.length),
      messages.length - 1,
    );
    message = messages[messageIndex] || "";
  }

  return { progress, message, complete };
};

export default useProgressSimulation;
