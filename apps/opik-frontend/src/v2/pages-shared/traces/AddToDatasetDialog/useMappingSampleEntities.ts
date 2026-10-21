import { useMemo } from "react";

import useTracesByIds from "@/api/traces/useTracesByIds";
import useSpansByIds from "@/api/traces/useSpansByIds";
import { Span, Trace } from "@/types/traces";
import { mergeSampleEntities } from "@/lib/traces";

export const MAPPING_SAMPLE_SIZE = 5;

type UseMappingSampleEntitiesParams = {
  validTraces: Array<Trace | Span>;
  validSpans: Array<Trace | Span>;
  hasOnlySpans: boolean;
  enabled: boolean;
};

const useMappingSampleEntities = ({
  validTraces,
  validSpans,
  hasOnlySpans,
  enabled,
}: UseMappingSampleEntitiesParams) => {
  const traceIds = useMemo(
    () =>
      enabled && !hasOnlySpans
        ? validTraces.slice(0, MAPPING_SAMPLE_SIZE).map((row) => row.id)
        : [],
    [enabled, hasOnlySpans, validTraces],
  );

  const spanIds = useMemo(
    () =>
      enabled && hasOnlySpans
        ? validSpans.slice(0, MAPPING_SAMPLE_SIZE).map((row) => row.id)
        : [],
    [enabled, hasOnlySpans, validSpans],
  );

  const traceResults = useTracesByIds({ traceIds, stripAttachments: true });
  const spanResults = useSpansByIds({ spanIds, stripAttachments: true });

  const results = hasOnlySpans ? spanResults : traceResults;

  const entities = useMemo(
    () =>
      results
        .map((result) => result.data)
        .filter((entity): entity is Trace | Span => Boolean(entity)),
    [results],
  );

  const treeData = useMemo(() => mergeSampleEntities(entities), [entities]);

  const isPending =
    (traceIds.length > 0 || spanIds.length > 0) &&
    results.some((result) => result.isPending);

  return { entities, treeData, isPending };
};

export default useMappingSampleEntities;
