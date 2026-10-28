import { useEffect, useRef } from "react";
import { QueryError, queryErrorStatus } from "./helpers";

// An autosave slower than the 404 retries saves the widget after its query already gave up; run it once more.
export default function useRefetchOnceSaved(
  retryNotFound: boolean,
  error: QueryError | null,
  refetch: () => unknown,
) {
  const wasSavedRef = useRef(!retryNotFound);
  useEffect(() => {
    const justSaved = !retryNotFound && !wasSavedRef.current;
    if (justSaved && error && queryErrorStatus(error) === 404) refetch();
    wasSavedRef.current = !retryNotFound;
  }, [retryNotFound, error, refetch]);
}
