import { useEffect, useState } from "react";

export function useTableScroll(tableRef: React.RefObject<HTMLElement>) {
  const [isScrolled, setIsScrolled] = useState(false);

  useEffect(() => {
    if (!tableRef.current) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        setIsScrolled(!entry.isIntersecting);
      },
      { threshold: 1 },
    );

    observer.observe(tableRef.current);

    return () => observer.disconnect();
  }, [tableRef]);

  return isScrolled;
}
