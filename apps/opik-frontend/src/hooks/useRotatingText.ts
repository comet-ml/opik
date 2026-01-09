import { useEffect, useState } from "react";

const DEFAULT_INTERVAL = 3000;

type UseRotatingTextParams = {
  texts: string[];
  interval?: number;
  enabled?: boolean;
};

const useRotatingText = ({
  texts,
  interval = DEFAULT_INTERVAL,
  enabled = true,
}: UseRotatingTextParams) => {
  const [index, setIndex] = useState(0);

  useEffect(() => {
    if (!enabled || texts.length === 0) return;

    const intervalId = setInterval(() => {
      setIndex((prev) => (prev + 1) % texts.length);
    }, interval);

    return () => clearInterval(intervalId);
  }, [enabled, texts.length, interval]);

  return {
    currentText: texts[index] ?? "",
    currentIndex: index,
  };
};

export default useRotatingText;

