import { DependencyList, useRef } from "react";
import isEqual from "fast-deep-equal";

export default function useDeepMemo<T>(factory: () => T, deps: DependencyList) {
  const ref = useRef<{ deps: DependencyList; value: T }>();

  if (!ref.current || !isEqual(deps, ref.current.deps)) {
    const value = factory();

    if (!ref.current || !isEqual(value, ref.current.value)) {
      ref.current = { deps, value };
    }
  }

  return ref.current.value;
}
