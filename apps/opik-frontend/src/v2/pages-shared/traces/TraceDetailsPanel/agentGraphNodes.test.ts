import { describe, expect, it } from "vitest";
import { Span, SPAN_TYPE, Trace } from "@/types/traces";
import {
  buildAgentGraphNodeIndex,
  getCurrentRunIndex,
  getNextNodeRow,
  isTraceRow,
  getRowAncestorNodeIds,
  toMermaidSafeId,
} from "./agentGraphNodes";

let startSecond = 0;

const makeSpan = (
  overrides: Partial<Span> & { id: string; name: string },
): Span => ({
  input: {},
  output: {},
  start_time: `2024-01-01T00:00:${String(startSecond++).padStart(2, "0")}Z`,
  end_time: "2024-01-01T00:01:00Z",
  duration: 1000,
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
  metadata: {},
  comments: [],
  tags: [],
  type: SPAN_TYPE.general,
  parent_span_id: "",
  trace_id: "trace-1",
  project_id: "project-1",
  ...overrides,
});

const langGraphSpan = (
  id: string,
  name: string,
  node: string,
  namespace: string,
  extra: Partial<Span> = {},
) =>
  makeSpan({
    id,
    name,
    metadata: { langgraph_node: node, langgraph_checkpoint_ns: namespace },
    ...extra,
  });

const internal = { _opik: { is_internal: true } };

describe("agentGraphNodes", () => {
  it("should escape mermaid ids the way LangGraph does", () => {
    expect(toMermaidSafeId("summarizer:sub_prepare")).toBe(
      "summarizer\\3asub_prepare",
    );
    expect(toMermaidSafeId("my node")).toBe("my\\20node");
    expect(toMermaidSafeId("plain_id-1")).toBe("plain_id-1");
  });

  it("should map every run of a LangGraph node to its node id in start order", () => {
    const spans = [
      langGraphSpan("w3", "worker", "worker", "worker:c", {
        start_time: "2024-01-01T00:00:30Z",
      }),
      langGraphSpan("w1", "worker", "worker", "worker:a", {
        start_time: "2024-01-01T00:00:10Z",
      }),
      langGraphSpan("w2", "worker", "worker", "worker:b", {
        start_time: "2024-01-01T00:00:20Z",
      }),
    ];

    const { rowsByNodeId } = buildAgentGraphNodeIndex(spans);

    expect(rowsByNodeId.get("worker")?.map((s) => s.id)).toEqual([
      "w1",
      "w2",
      "w3",
    ]);
  });

  it("should skip spans inside a LangGraph node and internal spans", () => {
    const spans = [
      langGraphSpan("router", "router", "router", "router:a"),
      langGraphSpan("route", "route", "router", "router:a", {
        parent_span_id: "router",
        metadata: {
          langgraph_node: "router",
          ...internal,
        },
      }),
      langGraphSpan("llm", "ChatOpenAI", "router", "router:a", {
        parent_span_id: "router",
      }),
    ];

    const { rowsByNodeId, nodeIdsByRowId } = buildAgentGraphNodeIndex(spans);

    expect(rowsByNodeId.get("router")?.map((s) => s.id)).toEqual(["router"]);
    expect(rowsByNodeId.has("ChatOpenAI")).toBe(false);
    expect(nodeIdsByRowId.has("route")).toBe(false);
  });

  it("should match subgraph nodes by their escaped path and keep same-named nodes apart", () => {
    const spans = [
      langGraphSpan("research-agent", "agent", "agent", "research:1|agent:2"),
      langGraphSpan("writer-agent", "agent", "agent", "writer:3|agent:4"),
      langGraphSpan("research", "research", "research", "research:1"),
    ];

    const { rowsByNodeId } = buildAgentGraphNodeIndex(spans);

    expect(rowsByNodeId.get("research\\3aagent")?.map((s) => s.id)).toEqual([
      "research-agent",
    ]);
    expect(rowsByNodeId.get("writer\\3aagent")?.map((s) => s.id)).toEqual([
      "writer-agent",
    ]);
    expect(rowsByNodeId.get("research")?.map((s) => s.id)).toEqual([
      "research",
    ]);
    expect(rowsByNodeId.has("agent")).toBe(false);
  });

  it("should match spans of other integrations by name", () => {
    const spans = [
      makeSpan({ id: "a", name: "weather agent" }),
      makeSpan({ id: "t", name: "get_weather", type: SPAN_TYPE.tool }),
    ];

    const { rowsByNodeId } = buildAgentGraphNodeIndex(spans);

    expect(rowsByNodeId.get("weather_agent")?.map((s) => s.id)).toEqual(["a"]);
    expect(rowsByNodeId.get("get_weather")?.map((s) => s.id)).toEqual(["t"]);
  });

  it("should collect node ids from the selected span up to the root", () => {
    const spans = [
      langGraphSpan("summarizer", "summarizer", "summarizer", "summarizer:1"),
      makeSpan({
        id: "wrapper",
        name: "LangGraph",
        parent_span_id: "summarizer",
        metadata: { langgraph_node: "summarizer", ...internal },
      }),
      langGraphSpan(
        "prepare",
        "sub_prepare",
        "sub_prepare",
        "summarizer:1|sub_prepare:2",
        { parent_span_id: "wrapper" },
      ),
      langGraphSpan(
        "llm",
        "ChatOpenAI",
        "sub_prepare",
        "summarizer:1|sub_prepare:2",
        {
          parent_span_id: "prepare",
        },
      ),
    ];
    const index = buildAgentGraphNodeIndex(spans);
    const spansById = new Map(spans.map((span) => [span.id, span]));

    expect(getRowAncestorNodeIds("llm", spansById, index)).toEqual([
      "summarizer:sub_prepare",
      "summarizer\\3asub_prepare",
      "summarizer",
    ]);
    expect(getRowAncestorNodeIds("missing", spansById, index)).toEqual([]);
  });

  it("should skip spans that arrived without a name or start time", () => {
    const spans = [
      { ...makeSpan({ id: "a", name: "agent" }), name: undefined },
      { ...makeSpan({ id: "b", name: "agent" }), start_time: undefined },
    ] as unknown as Span[];

    expect(
      buildAgentGraphNodeIndex(spans)
        .rowsByNodeId.get("agent")
        ?.map((s) => s.id),
    ).toEqual(["b"]);
  });

  it("should not map LangGraph __start__ and __end__ spans to nodes", () => {
    const spans = [langGraphSpan("s", "__start__", "__start__", "__start__:1")];

    expect(buildAgentGraphNodeIndex(spans).rowsByNodeId.size).toBe(0);
  });

  it("should match the trace when the root agent is logged as the trace", () => {
    const trace = {
      id: "trace-1",
      name: "travel_coordinator",
      start_time: "2024-01-01T00:00:00Z",
      metadata: {},
    } as Trace;
    const tool = makeSpan({ id: "t", name: "get_local_time" });
    const index = buildAgentGraphNodeIndex([trace, tool]);
    const itemsById = new Map<string, Span | Trace>([
      [trace.id, trace],
      [tool.id, tool],
    ]);

    expect(index.rowsByNodeId.get("travel_coordinator")?.[0].id).toBe(
      "trace-1",
    );
    expect(getRowAncestorNodeIds("t", itemsById, index)).toEqual([
      "get_local_time",
      "travel_coordinator",
    ]);
  });

  it("should cycle through the runs of a node", () => {
    const runs = [
      makeSpan({ id: "r1", name: "worker" }),
      makeSpan({ id: "r2", name: "worker" }),
    ];

    const rowsById = new Map(runs.map((run) => [run.id, run]));

    expect(getNextNodeRow(runs, "other", rowsById).id).toBe("r1");
    expect(getNextNodeRow(runs, "r1", rowsById).id).toBe("r2");
    expect(getNextNodeRow(runs, "r2", rowsById).id).toBe("r1");
  });

  it("should treat a span inside a run as that run", () => {
    const runs = [
      makeSpan({ id: "r1", name: "worker" }),
      makeSpan({ id: "r2", name: "worker" }),
      makeSpan({ id: "r3", name: "worker" }),
    ];
    const llm = makeSpan({
      id: "llm",
      name: "ChatOpenAI",
      parent_span_id: "r2",
    });
    const rowsById = new Map([...runs, llm].map((row) => [row.id, row]));

    expect(getCurrentRunIndex(runs, "llm", rowsById)).toBe(1);
    expect(getNextNodeRow(runs, "llm", rowsById).id).toBe("r3");
    expect(getCurrentRunIndex(runs, "missing", rowsById)).toBe(-1);
  });

  it("should tell the trace row from span rows", () => {
    expect(isTraceRow({ id: "t", name: "trace" } as Trace)).toBe(true);
    expect(isTraceRow(makeSpan({ id: "s", name: "span" }))).toBe(false);
  });

  it("should match rows by the graph node id the SDK logged", () => {
    const spans = [
      makeSpan({
        id: "react",
        name: "ReAct",
        metadata: { _opik: { graph_node_id: "module_1" } },
      }),
      makeSpan({
        id: "predict",
        name: "Predict",
        parent_span_id: "react",
        metadata: { _opik: { graph_node_id: "module_2" } },
      }),
      makeSpan({
        id: "other-predict",
        name: "Predict",
        metadata: { _opik: { graph_node_id: "module_3" } },
      }),
    ];

    const { rowsByNodeId } = buildAgentGraphNodeIndex(spans);

    expect(rowsByNodeId.get("module_2")?.map((s) => s.id)).toEqual(["predict"]);
    expect(rowsByNodeId.get("module_3")?.map((s) => s.id)).toEqual([
      "other-predict",
    ]);
    expect(rowsByNodeId.has("Predict")).toBe(false);
  });
});
