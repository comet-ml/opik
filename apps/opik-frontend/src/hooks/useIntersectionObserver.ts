import {
  useEffect,
  useState,
  useRef,
  useMemo,
  useCallback,
  RefObject,
} from "react";

// ============================================================================
// Types
// ============================================================================

export type IntersectionObserverOptions = {
  root?: Element | null;
  rootMargin?: string;
  threshold?: number | number[];
  triggerOnce?: boolean;
};

// ============================================================================
// Custom Hook
// ============================================================================

/**
 * Hook that detects when an element is visible in the viewport
 * using the Intersection Observer API
 *
 * @param elementRef - Ref to the element to observe
 * @param options - Intersection Observer configuration options
 * @param options.root - The element used as viewport for checking visibility (defaults to browser viewport)
 * @param options.rootMargin - Margin around the root element (defaults to "100px")
 * @param options.threshold - Percentage of target's visibility needed to trigger (defaults to 0.01)
 * @param options.triggerOnce - Whether to stop observing after first intersection (defaults to false)
 * @returns Boolean indicating if the element is currently intersecting
 *
 * @example
 * ```tsx
 * const LazyImage = () => {
 *   const imageRef = useRef<HTMLImageElement>(null);
 *   const isVisible = useIntersectionObserver(imageRef, { triggerOnce: true });
 *
 *   return (
 *     <img
 *       ref={imageRef}
 *       src={isVisible ? actualImage : placeholder}
 *       alt="Lazy loaded"
 *     />
 *   );
 * };
 * ```
 *
 * @remarks
 * - When `triggerOnce` is true, intersection state remains `true` after first trigger
 * - Uses memoized callbacks and options for optimal performance
 * - Properly cleans up observers on unmount or when dependencies change
 */
export const useIntersectionObserver = (
  elementRef: RefObject<Element>,
  options: IntersectionObserverOptions = {},
): boolean => {
  const {
    root = null,
    rootMargin = "100px",
    threshold = 0.01,
    triggerOnce = false,
  } = options;

  const [isIntersecting, setIsIntersecting] = useState(false);
  const hasTriggeredRef = useRef(false);

  // Memoize observer options to prevent unnecessary observer recreations
  const observerOptions = useMemo(
    () => ({
      root,
      rootMargin,
      threshold,
    }),
    [root, rootMargin, threshold],
  );

  // Memoize callback to prevent observer recreations on every render
  const handleIntersection = useCallback(
    (entries: IntersectionObserverEntry[]) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          setIsIntersecting(true);
          hasTriggeredRef.current = true;
        } else if (!triggerOnce && !hasTriggeredRef.current) {
          setIsIntersecting(false);
        }
      });
    },
    [triggerOnce],
  );

  useEffect(() => {
    const element = elementRef.current;

    // Early return if element doesn't exist
    if (!element) {
      return;
    }

    // Early return if already triggered and triggerOnce is enabled
    if (triggerOnce && hasTriggeredRef.current) {
      return;
    }

    const observer = new IntersectionObserver(
      handleIntersection,
      observerOptions,
    );

    observer.observe(element);

    // Cleanup function
    return () => {
      observer.unobserve(element);
      observer.disconnect();
    };
  }, [elementRef, observerOptions, handleIntersection, triggerOnce]);

  return isIntersecting;
};
