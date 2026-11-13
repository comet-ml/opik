import React, { useMemo } from "react";
import useLoop from "@/api/automations/useLoop";
import Loader from "@/shared/Loader/Loader";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/ui/table";
import { asCount } from "./helpers";
import { layoutLoop, NODE_HEIGHT, NODE_WIDTH } from "./loopLayout";

const KIND_LABELS: Record<string, string> = {
  project: "Production traces",
  judge: "Judge",
  annotation_queue: "Annotation queue",
  test_suite: "Test suite",
  prompt: "Prompt",
  ci: "CI",
  dataset: "Dataset",
};

const PRODUCED = [
  ["fired", "Fired"],
  ["matched", "Matched"],
  ["items_written", "Items written"],
  ["steps_failed", "Steps failed"],
] as const;

const truncate = (text: string, max = 22) =>
  text.length > max ? `${text.slice(0, max - 1)}…` : text;

type LoopSectionProps = {
  projectId: string;
};

const LoopSection: React.FC<LoopSectionProps> = ({ projectId }) => {
  const { data, isPending, isError } = useLoop({ projectId });
  const layout = useMemo(() => (data ? layoutLoop(data) : null), [data]);
  const nodeNames = useMemo(
    () => new Map((data?.nodes ?? []).map((node) => [node.id, node.name])),
    [data],
  );

  const nameOf = (id: string) => nodeNames.get(id) ?? id;

  let body: React.ReactNode;
  if (isPending) {
    body = <Loader className="min-h-32" />;
  } else if (isError || !data || !layout) {
    body = (
      <p className="comet-body-s text-muted-slate">
        The loop could not be read.
      </p>
    );
  } else if (data.edges.length === 0) {
    body = (
      <p className="comet-body-s text-muted-slate">
        Nothing runs on this project yet. Start from the recommendations below:
        the first one sets up Collect, which keeps failing production traces in
        a test suite.
      </p>
    );
  } else {
    body = (
      <div className="flex flex-col gap-3">
        <div className="overflow-x-auto rounded-md border border-border bg-background p-2">
          <svg
            width={layout.width}
            height={layout.height}
            viewBox={`0 0 ${layout.width} ${layout.height}`}
            role="img"
            aria-label="The automations that run on this project, drawn from what they read to what they write"
          >
            <defs>
              <marker
                id="loop-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" className="fill-primary" />
              </marker>
            </defs>
            {layout.edges.map((edge) => (
              <g key={edge.key}>
                <path
                  d={edge.path}
                  className="fill-none stroke-primary"
                  strokeWidth={1.5}
                  markerEnd="url(#loop-arrow)"
                >
                  <title>{edge.name}</title>
                </path>
                <text
                  x={edge.labelX}
                  y={edge.labelY - 6}
                  textAnchor="middle"
                  className="fill-muted-slate text-[11px]"
                >
                  {edge.recipe ?? truncate(edge.name, 16)}
                </text>
              </g>
            ))}
            {layout.nodes.map((node) => (
              <g key={node.id} transform={`translate(${node.x} ${node.y})`}>
                <rect
                  width={NODE_WIDTH}
                  height={NODE_HEIGHT}
                  rx={6}
                  className="fill-background stroke-border"
                />
                <text
                  x={10}
                  y={17}
                  className="fill-muted-slate text-[10px] uppercase"
                >
                  {KIND_LABELS[node.kind] ?? node.kind}
                </text>
                <text x={10} y={33} className="fill-foreground text-xs">
                  <title>{node.name}</title>
                  {truncate(node.name)}
                </text>
              </g>
            ))}
          </svg>
        </div>
        {layout.skipped > 0 && (
          <p className="comet-body-xs text-muted-slate">
            {layout.skipped} edge end{layout.skipped === 1 ? "" : "s"} name an
            object the loop did not list; they appear in the table only.
          </p>
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Automation</TableHead>
              <TableHead>Recipe</TableHead>
              <TableHead>Reads → writes</TableHead>
              {PRODUCED.map(([key, label]) => (
                <TableHead key={key} className="text-right">
                  {label}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.edges.map((edge) => (
              <TableRow key={edge.automation_id}>
                <TableCell className="comet-body-s">{edge.name}</TableCell>
                <TableCell className="comet-body-s">
                  {edge.recipe ?? "manual"}
                </TableCell>
                <TableCell className="comet-body-s">
                  {nameOf(edge.from)} →{" "}
                  {edge.to.length ? edge.to.map(nameOf).join(", ") : "nothing"}
                </TableCell>
                {PRODUCED.map(([key]) => (
                  <TableCell key={key} className="comet-body-s text-right">
                    {asCount(edge.produced[key]) ?? "–"}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    );
  }

  return (
    <section>
      <h2 className="comet-body-accented">The loop</h2>
      <p className="comet-body-s mb-3 text-muted-slate">
        Every running automation, from what it reads to what it writes
        {data
          ? `, with what it produced over the last ${data.window_days} days`
          : ""}
        .
      </p>
      {body}
    </section>
  );
};

export default LoopSection;
