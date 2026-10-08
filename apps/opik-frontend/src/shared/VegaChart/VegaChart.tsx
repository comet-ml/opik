import React, { useEffect, useRef, useState } from "react";

import { useTheme } from "@/contexts/theme-provider";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";
import { renderVegaChart, VegaRows, VegaSpec } from "@/lib/charts/vega";
import { cn } from "@/lib/utils";
import ChartSkeleton from "./ChartSkeleton";
import RowsTable, { tableColumns } from "./RowsTable";

type VegaChartProps = {
  spec: VegaSpec;
  rows?: VegaRows | null;
  height?: number | "container";
  className?: string;
};

const VegaChart: React.FunctionComponent<VegaChartProps> = (props) => {
  const columns = tableColumns(props.spec);
  if (columns) {
    return (
      <div className={cn("size-full", props.className)}>
        <RowsTable rows={props.rows ?? []} columns={columns} />
      </div>
    );
  }
  return <VegaSpecChart {...props} />;
};

const VegaSpecChart: React.FunctionComponent<VegaChartProps> = ({
  spec,
  rows,
  height = "container",
  className,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const { themeMode } = useTheme();
  const { colorMap } = useWorkspaceColorMap();
  const [rendered, setRendered] = useState(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    let cancelled = false;
    let finalize: (() => void) | null = null;
    setError(null);

    renderVegaChart(
      el,
      { spec, rows },
      { colorOverride: (label) => colorMap?.[label], height },
    )
      .then((dispose) => {
        if (cancelled) {
          dispose();
          return;
        }
        finalize = dispose;
        setRendered(true);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Chart render failed");
      });

    return () => {
      cancelled = true;
      finalize?.();
    };
    // themeMode: the config is read from CSS variables, so a theme switch needs a re-render.
  }, [spec, rows, height, colorMap, themeMode]);

  if (error) {
    // The data is still worth showing when the spec can't be drawn.
    if (rows?.length) {
      return (
        <RowsTable
          rows={rows}
          note="Couldn't draw the chart; showing the data."
        />
      );
    }
    return (
      <div className="comet-body-s flex size-full items-center justify-center p-2 text-center text-muted-slate">
        {error}
      </div>
    );
  }

  return (
    <div className={cn("relative size-full", className)}>
      {!rendered && <ChartSkeleton className="absolute inset-0" />}
      <div ref={containerRef} className="size-full" />
    </div>
  );
};

export default VegaChart;
