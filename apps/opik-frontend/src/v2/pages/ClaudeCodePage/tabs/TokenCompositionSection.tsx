import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import {
  Bot,
  Brain,
  ChevronRight,
  FileText,
  History,
  Layers,
  MessageCircleReply,
  Paperclip,
  Plug,
  Sparkles,
  Terminal,
  User,
  Wrench,
  X,
  Zap,
} from "lucide-react";
import { Card } from "@/ui/card";
import { Button } from "@/ui/button";
import { TokenComposition, SankeyFlow } from "../types";
import { cn } from "@/lib/utils";

const GROUP_COLOR: Record<NonNullable<SankeyFlow["group"]>, string> = {
  dynamic: "hsl(217 89% 58%)",
  static: "hsl(225 14% 50%)",
  mcp: "hsl(265 70% 60%)",
  thinking: "hsl(287 85% 56%)",
  tool: "hsl(22 92% 55%)",
  text: "hsl(165 75% 40%)",
  skill: "hsl(48 96% 50%)",
};

const ICON_BY_LABEL: Record<
  string,
  React.ComponentType<{ className?: string }>
> = {
  "Prior assistant context": History,
  "Tool results": Terminal,
  "User prompts": User,
  "File attachments": Paperclip,
  "Static overhead": Layers,
  Thinking: Brain,
  "Built-in tool calls": Wrench,
  "MCP tool calls": Plug,
  "Skill invocations": Sparkles,
  "Assistant text": MessageCircleReply,
  "Base Claude Code prompt + wrap": FileText,
};

function iconForLabel(
  label: string,
): React.ComponentType<{ className?: string }> {
  if (ICON_BY_LABEL[label]) return ICON_BY_LABEL[label];
  if (label.startsWith("MCP servers")) return Plug;
  if (label.startsWith("Skills loaded")) return Sparkles;
  if (label.startsWith("Skill ")) return Sparkles;
  if (label.startsWith("MCP ")) return Plug;
  return Zap;
}

const fmt = (n: number) =>
  `$${n.toLocaleString(undefined, { maximumFractionDigits: 0 })}`;

type DrillKey = NonNullable<SankeyFlow["drillKey"]>;

type AgentId = "claude-code" | "codex";

interface AgentConfig {
  id: AgentId;
  name: string;
  icon: React.ComponentType<{ className?: string }>;
  color: string;
  /** Mockup share of total spend — used for the small $/% label on each card. */
  sharePct: number;
}

const AGENTS: AgentConfig[] = [
  {
    id: "claude-code",
    name: "Claude Code",
    icon: Bot,
    color: "hsl(22 92% 55%)", // Claude orange
    sharePct: 0.71,
  },
  {
    id: "codex",
    name: "Codex",
    icon: Bot,
    color: "hsl(0 0% 12%)", // near-black
    sharePct: 0.29,
  },
];

interface LaneCardProps {
  lane: SankeyFlow;
  side: "left" | "right";
  totalForPct: number;
  onDrill: (key: DrillKey) => void;
  activeDrill: DrillKey | null;
}

const LaneCard = React.forwardRef<HTMLDivElement, LaneCardProps>(
  function LaneCard({ lane, side, totalForPct, onDrill, activeDrill }, ref) {
    const Icon = iconForLabel(lane.label);
    const color = GROUP_COLOR[lane.group ?? "static"];
    const pct = totalForPct > 0 ? (lane.value / totalForPct) * 100 : 0;
    const drillable = !!lane.drillKey;
    const active = drillable && activeDrill === lane.drillKey;

    return (
      <div
        ref={ref}
        className={cn(
          "flex items-center gap-3 rounded-lg border bg-background px-3 py-2 shadow-sm transition-colors",
          drillable && "cursor-pointer hover:border-foreground/40",
          active && "border-foreground/60 ring-1 ring-foreground/20",
          side === "right" && "flex-row-reverse text-right",
        )}
        onClick={drillable ? () => onDrill(lane.drillKey!) : undefined}
      >
        <div
          className="flex size-9 shrink-0 items-center justify-center rounded-md"
          style={{ backgroundColor: `${color}1f`, color }}
        >
          <Icon className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="comet-body-s-accented truncate text-foreground">
            {lane.label}
            {drillable ? (
              <ChevronRight className="ml-0.5 inline size-3 -translate-y-px" />
            ) : null}
          </div>
          <div className="comet-body-xs whitespace-nowrap text-muted-foreground">
            <span className="font-mono">{fmt(lane.value)}</span>
            <span className="mx-1">·</span>
            <span>{pct.toFixed(1)}%</span>
          </div>
        </div>
      </div>
    );
  },
);

interface ConnectorPath {
  d: string;
  color: string;
  width: number;
  /** Lane value — used to probability-weight the token stream. */
  value: number;
}

interface FlyingDot {
  id: string;
  pathD: string;
  color: string;
  /** Per-dot animation start delay (ms), used to stagger the stream. */
  delayMs: number;
}

/** Pick an index from `paths`, weighted by each path's value. */
const weightedPick = (paths: ConnectorPath[]): number => {
  const total = paths.reduce((a, p) => a + p.value, 0);
  if (total <= 0) return 0;
  let r = Math.random() * total;
  for (let i = 0; i < paths.length; i++) {
    r -= paths[i].value;
    if (r <= 0) return i;
  }
  return paths.length - 1;
};

interface DiagramProps {
  composition: TokenComposition;
  onDrill: (key: DrillKey) => void;
  activeDrill: DrillKey | null;
  selectedAgent: AgentId | null;
  onSelectAgent: (id: AgentId | null) => void;
}

interface AgentCardProps {
  agent: AgentConfig;
  spend: number;
  selected: boolean;
  onClick: () => void;
}

const AgentCard = React.forwardRef<HTMLDivElement, AgentCardProps>(
  function AgentCard({ agent, spend, selected, onClick }, ref) {
    const Icon = agent.icon;
    return (
      <div
        ref={ref}
        role="button"
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onClick();
          }
        }}
        className={cn(
          "flex cursor-pointer items-center gap-3 rounded-xl border bg-background px-4 py-3 transition-all",
          selected
            ? "shadow-md"
            : "opacity-70 shadow-sm hover:opacity-100 hover:shadow-md",
        )}
        style={
          selected
            ? {
                boxShadow: `0 0 0 1.5px ${agent.color}, 0 6px 18px ${agent.color}1f`,
              }
            : undefined
        }
      >
        <div
          className="flex size-10 shrink-0 items-center justify-center rounded-full"
          style={{ backgroundColor: `${agent.color}1f`, color: agent.color }}
        >
          <Icon className="size-5" />
        </div>
        <div className="min-w-0">
          <div className="comet-body-s-accented truncate text-foreground">
            {agent.name}
          </div>
          <div className="comet-body-xs text-muted-foreground">
            <span className="font-mono">{fmt(spend)}</span>
            <span className="mx-1">·</span>
            <span>{(agent.sharePct * 100).toFixed(0)}%</span>
          </div>
        </div>
      </div>
    );
  },
);

const TokenSystemDiagram: React.FC<DiagramProps> = ({
  composition,
  onDrill,
  activeDrill,
  selectedAgent,
  onSelectAgent,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const agentsBoxRef = useRef<HTMLDivElement>(null);
  const agentRefs = useRef<Record<AgentId, HTMLDivElement | null>>({
    "claude-code": null,
    codex: null,
  });
  const leftRefs = useRef<(HTMLDivElement | null)[]>([]);
  const rightRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [paths, setPaths] = useState<{
    left: ConnectorPath[];
    right: ConnectorPath[];
    size: { w: number; h: number };
  }>({ left: [], right: [], size: { w: 0, h: 0 } });
  const [dots, setDots] = useState<FlyingDot[]>([]);
  const [pulsing, setPulsing] = useState(false);

  const totalSpend = composition.totalInput + composition.totalOutput;

  const recomputePaths = () => {
    const c = containerRef.current;
    const ac = agentsBoxRef.current;
    if (!c || !ac) return;
    const cRect = c.getBoundingClientRect();
    const acRect = ac.getBoundingClientRect();
    // Anchor connectors at the LEFT/RIGHT edge of the *stack* containing both
    // agent cards, with attach points fanned across the stack's vertical span.
    const stackLeftX = acRect.left - cRect.left;
    const stackRightX = acRect.right - cRect.left;
    const stackTopY = acRect.top - cRect.top;
    const stackBotY = acRect.bottom - cRect.top;
    const stackHeight = stackBotY - stackTopY;

    // Pad attach points inward a bit so they don't kiss the corners.
    const pad = Math.min(18, stackHeight * 0.12);
    const usableTop = stackTopY + pad;
    const usableBot = stackBotY - pad;
    const fanY = (i: number, n: number) =>
      n <= 1
        ? (usableTop + usableBot) / 2
        : usableTop + (i / (n - 1)) * (usableBot - usableTop);

    const maxIn = Math.max(...composition.input.map((f) => f.value), 1);
    const maxOut = Math.max(...composition.output.map((f) => f.value), 1);

    const widthFor = (v: number, max: number) =>
      Math.max(1.5, Math.sqrt(v / max) * 14);

    const left = composition.input
      .map((lane, i, arr): ConnectorPath | null => {
        const el = leftRefs.current[i];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const x = r.right - cRect.left;
        const y = (r.top + r.bottom) / 2 - cRect.top;
        const yAttach = fanY(i, arr.length);
        const midX = (x + stackLeftX) / 2;
        const d = `M ${x} ${y} C ${midX} ${y}, ${midX} ${yAttach}, ${stackLeftX} ${yAttach}`;
        return {
          d,
          color: GROUP_COLOR[lane.group ?? "static"],
          width: widthFor(lane.value, maxIn),
          value: lane.value,
        };
      })
      .filter((p): p is ConnectorPath => !!p);

    const right = composition.output
      .map((lane, i, arr): ConnectorPath | null => {
        const el = rightRefs.current[i];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const x = r.left - cRect.left;
        const y = (r.top + r.bottom) / 2 - cRect.top;
        const yAttach = fanY(i, arr.length);
        const midX = (stackRightX + x) / 2;
        const d = `M ${stackRightX} ${yAttach} C ${midX} ${yAttach}, ${midX} ${y}, ${x} ${y}`;
        return {
          d,
          color: GROUP_COLOR[lane.group ?? "static"],
          width: widthFor(lane.value, maxOut),
          value: lane.value,
        };
      })
      .filter((p): p is ConnectorPath => !!p);

    setPaths({
      left,
      right,
      size: { w: cRect.width, h: cRect.height },
    });
  };

  useLayoutEffect(() => {
    recomputePaths();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [composition]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => recomputePaths());
    ro.observe(el);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Coordinated streaming request lifecycle:
  //   Phase 1: a probability-weighted stream of LHS dots flows INTO the Harness
  //   Phase 2: at hand-off, the Harness gives a subtle pulse AND a
  //            probability-weighted stream of RHS dots flows out — evoking
  //            an LLM autoregressively generating tokens into categories.
  useEffect(() => {
    if (paths.left.length === 0 || paths.right.length === 0) return;
    let counter = 0;
    const timeouts: ReturnType<typeof setTimeout>[] = [];
    const DOT_FLY_MS = 700;
    const STAGGER_MS = 55;
    const STREAM_IN = 8;
    const STREAM_OUT = 14;
    const PULSE_MS = 500;
    const LHS_DURATION = (STREAM_IN - 1) * STAGGER_MS + DOT_FLY_MS;
    const RHS_DURATION = (STREAM_OUT - 1) * STAGGER_MS + DOT_FLY_MS;
    const CYCLE_MS = LHS_DURATION + RHS_DURATION + 250;

    const fireRequest = () => {
      counter++;
      const reqId = `r${counter}-${Date.now()}`;

      const inDots: FlyingDot[] = Array.from({ length: STREAM_IN }, (_, j) => {
        const idx = weightedPick(paths.left);
        return {
          id: `${reqId}-in-${j}`,
          pathD: paths.left[idx].d,
          color: paths.left[idx].color,
          delayMs: j * STAGGER_MS,
        };
      });
      const outDots: FlyingDot[] = Array.from(
        { length: STREAM_OUT },
        (_, j) => {
          const idx = weightedPick(paths.right);
          return {
            id: `${reqId}-out-${j}`,
            pathD: paths.right[idx].d,
            color: paths.right[idx].color,
            delayMs: j * STAGGER_MS,
          };
        },
      );

      // Phase 1: spawn entire LHS stream; each dot's animation-delay handles
      // the stagger.
      setDots((cur) => [...cur, ...inDots]);

      // Phase 2: at hand-off, swap LHS for RHS and fire pulse.
      timeouts.push(
        setTimeout(() => {
          setDots((cur) => [
            ...cur.filter((d) => !inDots.some((id) => id.id === d.id)),
            ...outDots,
          ]);
          setPulsing(true);
        }, LHS_DURATION),
      );

      // Clear pulse class so it can re-trigger next cycle.
      timeouts.push(
        setTimeout(() => {
          setPulsing(false);
        }, LHS_DURATION + PULSE_MS),
      );

      // Cleanup RHS dots after the last one lands.
      timeouts.push(
        setTimeout(() => {
          setDots((cur) =>
            cur.filter((d) => !outDots.some((od) => od.id === d.id)),
          );
        }, LHS_DURATION + RHS_DURATION),
      );
    };

    fireRequest();
    const interval = setInterval(fireRequest, CYCLE_MS);
    return () => {
      clearInterval(interval);
      timeouts.forEach(clearTimeout);
      setDots([]);
      setPulsing(false);
    };
  }, [paths]);

  const handleAgentClick = (id: AgentId) =>
    onSelectAgent(selectedAgent === id ? null : id);

  return (
    <div
      ref={containerRef}
      className="relative grid items-center gap-x-10 sm:gap-x-16"
      style={{
        gridTemplateColumns: "minmax(220px, 1fr) auto minmax(220px, 1fr)",
      }}
    >
      <style>{`
        @keyframes cc-fly {
          0%   { offset-distance: 0%;   opacity: 0; }
          12%  { opacity: 1; }
          88%  { opacity: 1; }
          100% { offset-distance: 100%; opacity: 0; }
        }
        @keyframes cc-harness-pulse {
          0%   { box-shadow: 0 0 0 0  hsla(0, 0%, 40%, 0.08); }
          100% { box-shadow: 0 0 0 5px hsla(0, 0%, 40%, 0); }
        }
        .cc-harness-pulsing {
          animation: cc-harness-pulse 500ms ease-out forwards;
        }
      `}</style>

      <svg
        className="pointer-events-none absolute inset-0"
        width={paths.size.w}
        height={paths.size.h}
      >
        {[...paths.left, ...paths.right].map((p, i) => (
          <path
            key={`s-${i}`}
            d={p.d}
            stroke={p.color}
            strokeWidth={p.width}
            strokeOpacity={0.5}
            fill="none"
            strokeLinecap="round"
          />
        ))}
      </svg>

      <div
        className="pointer-events-none absolute inset-0"
        style={{ zIndex: 20 }}
        aria-hidden
      >
        {dots.map((dot) => (
          <span
            key={dot.id}
            className="absolute left-0 top-0 inline-block leading-none"
            style={{
              offsetPath: `path('${dot.pathD}')`,
              offsetDistance: "0%",
              offsetRotate: "0deg",
              color: dot.color,
              fontSize: 7,
              animation: `cc-fly 700ms linear ${dot.delayMs}ms both`,
              willChange: "offset-distance, opacity",
            }}
          >
            ◉
          </span>
        ))}
      </div>

      <div className="relative z-10 flex flex-col gap-2">
        {composition.input.map((lane, i) => (
          <LaneCard
            key={lane.label}
            ref={(el) => {
              leftRefs.current[i] = el;
            }}
            lane={lane}
            side="left"
            totalForPct={composition.totalInput}
            onDrill={onDrill}
            activeDrill={activeDrill}
          />
        ))}
      </div>

      <div
        ref={agentsBoxRef}
        className={cn(
          "relative z-10 rounded-2xl border border-dashed border-foreground/25 bg-background px-3 pb-3 pt-5",
          pulsing && "cc-harness-pulsing",
        )}
      >
        <span className="comet-body-xs absolute -top-2 left-1/2 -translate-x-1/2 bg-background px-2 uppercase tracking-wide text-muted-foreground">
          Harness
        </span>
        <div className="flex flex-col gap-3">
          {AGENTS.map((agent) => (
            <AgentCard
              key={agent.id}
              ref={(el) => {
                agentRefs.current[agent.id] = el;
              }}
              agent={agent}
              spend={Math.round(totalSpend * agent.sharePct)}
              selected={selectedAgent === agent.id}
              onClick={() => handleAgentClick(agent.id)}
            />
          ))}
        </div>
      </div>

      <div className="relative z-10 flex flex-col gap-2">
        {composition.output.map((lane, i) => (
          <LaneCard
            key={lane.label}
            ref={(el) => {
              rightRefs.current[i] = el;
            }}
            lane={lane}
            side="right"
            totalForPct={composition.totalOutput}
            onDrill={onDrill}
            activeDrill={activeDrill}
          />
        ))}
      </div>
    </div>
  );
};

interface DrillProps {
  title: string;
  rows: SankeyFlow[];
  total: number;
  onClose: () => void;
}

const DrillPanel: React.FC<DrillProps> = ({ title, rows, total, onClose }) => {
  const maxVal = Math.max(...rows.map((r) => r.value), 1);
  return (
    <div className="rounded-md border bg-muted/30 p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <div className="comet-body-accented text-foreground">{title}</div>
          <div className="comet-body-xs text-muted-foreground">
            {rows.length} items · {fmt(total)} / month total
          </div>
        </div>
        <Button variant="ghost" size="icon" onClick={onClose}>
          <X className="size-4" />
        </Button>
      </div>
      <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
        {rows.map((r) => {
          const pct = (r.value / total) * 100;
          const barWidth = (r.value / maxVal) * 100;
          const color = GROUP_COLOR[r.group ?? "static"];
          return (
            <div
              key={r.label}
              className="flex items-center gap-3 rounded-md bg-background px-3 py-2 shadow-sm"
            >
              <span
                className="size-2 shrink-0 rounded-full"
                style={{ backgroundColor: color }}
              />
              <div className="comet-body-s min-w-0 flex-1 truncate text-foreground">
                {r.label}
              </div>
              <div className="hidden h-1.5 w-24 overflow-hidden rounded-full bg-muted md:block">
                <div
                  className="h-full"
                  style={{ width: `${barWidth}%`, backgroundColor: color }}
                />
              </div>
              <div className="comet-body-s w-16 text-right font-mono">
                {fmt(r.value)}
              </div>
              <div className="comet-body-xs w-12 text-right text-muted-foreground">
                {pct.toFixed(1)}%
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export interface UsageAnalysis {
  grade: string;
  label: string;
  /** "green" | "yellow" | "red" — drives badge color. */
  tone: "green" | "yellow" | "red";
  recoverableUsd: number;
  recoverablePct: number;
  activeUsers: number;
  totalUsers: number;
  activeTeams: number;
  /** Top 1–2 levers, lowercase, e.g. "tighter /compact thresholds". */
  topLevers: string[];
}

interface Props {
  composition: TokenComposition;
  windowDays: 7 | 30 | 90;
  analysis: UsageAnalysis;
}

// Multiply every value in a flow array by `scale` so per-agent filtering
// produces realistic-looking subtotals across all lanes + breakdowns.
const scaleFlows = (flows: SankeyFlow[], scale: number): SankeyFlow[] =>
  flows.map((f) => ({ ...f, value: Math.max(0, Math.round(f.value * scale)) }));

const scaleComposition = (
  c: TokenComposition,
  scale: number,
): TokenComposition => ({
  ...c,
  totalInput: Math.round(c.totalInput * scale),
  totalOutput: Math.round(c.totalOutput * scale),
  input: scaleFlows(c.input, scale),
  output: scaleFlows(c.output, scale),
  mcpBreakdown: scaleFlows(c.mcpBreakdown, scale),
  toolBreakdown: scaleFlows(c.toolBreakdown, scale),
  staticBreakdown: scaleFlows(c.staticBreakdown, scale),
  skillsLoadedBreakdown: scaleFlows(c.skillsLoadedBreakdown, scale),
  mcpCallsBreakdown: scaleFlows(c.mcpCallsBreakdown, scale),
  skillCallsBreakdown: scaleFlows(c.skillCallsBreakdown, scale),
  priorAssistantBreakdown: scaleFlows(c.priorAssistantBreakdown, scale),
  toolResultsBreakdown: scaleFlows(c.toolResultsBreakdown, scale),
  userPromptsBreakdown: scaleFlows(c.userPromptsBreakdown, scale),
  fileAttachmentsBreakdown: scaleFlows(c.fileAttachmentsBreakdown, scale),
  thinkingBreakdown: scaleFlows(c.thinkingBreakdown, scale),
  assistantTextBreakdown: scaleFlows(c.assistantTextBreakdown, scale),
});

export const TokenCompositionSection: React.FC<Props> = ({
  composition: rawComposition,
  analysis,
}) => {
  const [drill, setDrill] = useState<DrillKey | null>(null);
  const [selectedAgent, setSelectedAgent] = useState<AgentId | null>(null);
  const selectedAgentConfig = selectedAgent
    ? AGENTS.find((a) => a.id === selectedAgent)
    : null;
  // When an agent is selected, scale every value down to that agent's share
  // so the rest of the diagram (totals, lanes, drill-downs) all reflect the
  // filter naturally.
  const composition = selectedAgentConfig
    ? scaleComposition(rawComposition, selectedAgentConfig.sharePct)
    : rawComposition;
  const total = composition.totalInput + composition.totalOutput;

  const toneClasses: Record<
    UsageAnalysis["tone"],
    { bg: string; text: string }
  > = {
    green: { bg: "bg-chart-green/15", text: "text-chart-green" },
    yellow: { bg: "bg-chart-yellow/15", text: "text-chart-yellow" },
    red: { bg: "bg-chart-red/15", text: "text-chart-red" },
  };
  const tone = toneClasses[analysis.tone];
  const leversText =
    analysis.topLevers.length === 0
      ? ""
      : analysis.topLevers.length === 1
        ? analysis.topLevers[0]
        : `${analysis.topLevers.slice(0, -1).join(", ")} and ${
            analysis.topLevers[analysis.topLevers.length - 1]
          }`;

  const drillInfo: Record<
    DrillKey,
    { title: string; rows: SankeyFlow[]; total: number }
  > = {
    mcp: {
      title: "MCP servers — overhead per server (just from being loaded)",
      rows: composition.mcpBreakdown,
      total: composition.mcpBreakdown.reduce((a, b) => a + b.value, 0),
    },
    tools: {
      title: "Built-in tool calls — per-tool breakdown",
      rows: composition.toolBreakdown,
      total: composition.toolBreakdown.reduce((a, b) => a + b.value, 0),
    },
    static: {
      title: "Static overhead — per-source breakdown",
      rows: composition.staticBreakdown,
      total: composition.staticBreakdown.reduce((a, b) => a + b.value, 0),
    },
    skills_loaded: {
      title: "Skills loaded — body of each invoked skill, re-sent per turn",
      rows: composition.skillsLoadedBreakdown,
      total: composition.skillsLoadedBreakdown.reduce((a, b) => a + b.value, 0),
    },
    mcp_calls: {
      title: "MCP tool calls — cost of invoking each MCP server's tools",
      rows: composition.mcpCallsBreakdown,
      total: composition.mcpCallsBreakdown.reduce((a, b) => a + b.value, 0),
    },
    skill_calls: {
      title: "Skill invocations — cost of the Skill tool_use blocks themselves",
      rows: composition.skillCallsBreakdown,
      total: composition.skillCallsBreakdown.reduce((a, b) => a + b.value, 0),
    },
    prior_assistant: {
      title: "Prior assistant context — by session length",
      rows: composition.priorAssistantBreakdown,
      total: composition.priorAssistantBreakdown.reduce(
        (a, b) => a + b.value,
        0,
      ),
    },
    tool_results: {
      title: "Tool results — which tool is dumping the most context back",
      rows: composition.toolResultsBreakdown,
      total: composition.toolResultsBreakdown.reduce((a, b) => a + b.value, 0),
    },
    user_prompts: {
      title: "User prompts — distribution by prompt size",
      rows: composition.userPromptsBreakdown,
      total: composition.userPromptsBreakdown.reduce((a, b) => a + b.value, 0),
    },
    file_attachments: {
      title: "File attachments — by content type",
      rows: composition.fileAttachmentsBreakdown,
      total: composition.fileAttachmentsBreakdown.reduce(
        (a, b) => a + b.value,
        0,
      ),
    },
    thinking: {
      title: "Thinking — by model and effort level",
      rows: composition.thinkingBreakdown,
      total: composition.thinkingBreakdown.reduce((a, b) => a + b.value, 0),
    },
    assistant_text: {
      title: "Assistant text — by output category",
      rows: composition.assistantTextBreakdown,
      total: composition.assistantTextBreakdown.reduce(
        (a, b) => a + b.value,
        0,
      ),
    },
  };

  return (
    <Card className="overflow-hidden p-0">
      <div className="border-b px-5 py-4">
        <div className="flex items-start justify-between gap-6">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <div className="comet-title-s text-foreground">
                Coding agent usage
              </div>
              <span
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5",
                  tone.bg,
                )}
              >
                <span
                  className={cn("comet-body-s-accented font-mono", tone.text)}
                >
                  {analysis.grade}
                </span>
                <span className={cn("comet-body-xs", tone.text)}>
                  {analysis.label}
                </span>
              </span>
            </div>
            <div className="comet-body-s mt-1.5 text-muted-foreground">
              Adoption is strong —{" "}
              <span className="text-foreground">
                {analysis.activeUsers} of {analysis.totalUsers} users active
                across {analysis.activeTeams} teams
              </span>
              . About{" "}
              <span className="font-medium text-foreground">
                ~{analysis.recoverablePct}% of monthly spend (
                {fmt(analysis.recoverableUsd)}/mo)
              </span>{" "}
              is recoverable
              {leversText ? `, mostly via ${leversText}` : ""}.
            </div>
          </div>
          <div className="flex shrink-0 gap-6 text-right">
            <div>
              <div className="comet-body-xs text-muted-foreground">Input</div>
              <div className="comet-title-m text-foreground">
                {fmt(composition.totalInput)}
              </div>
            </div>
            <div>
              <div className="comet-body-xs text-muted-foreground">Output</div>
              <div className="comet-title-m text-foreground">
                {fmt(composition.totalOutput)}
              </div>
            </div>
            <div>
              <div className="comet-body-xs text-muted-foreground">
                Combined
              </div>
              <div className="comet-title-m text-foreground">{fmt(total)}</div>
            </div>
          </div>
        </div>
      </div>

      <div className="p-5">
        <div
          className="mb-3 grid items-center gap-x-10 sm:gap-x-16"
          style={{
            gridTemplateColumns: "minmax(220px, 1fr) auto minmax(220px, 1fr)",
          }}
        >
          <div className="comet-body-xs uppercase tracking-wide text-muted-foreground">
            Input — where prompt tokens come from
          </div>
          <div className="comet-body-xs text-muted-foreground">&nbsp;</div>
          <div className="comet-body-xs text-right uppercase tracking-wide text-muted-foreground">
            Output — where response tokens go
          </div>
        </div>

        {selectedAgentConfig ? (
          <div className="mb-3 flex items-center justify-between rounded-md border bg-muted/40 px-3 py-2">
            <div className="comet-body-xs text-muted-foreground">
              Filtered to{" "}
              <span
                className="comet-body-xs-accented"
                style={{ color: selectedAgentConfig.color }}
              >
                {selectedAgentConfig.name}
              </span>{" "}
              · {(selectedAgentConfig.sharePct * 100).toFixed(0)}% of org spend
            </div>
            <Button
              variant="link"
              size="sm"
              className="h-auto p-0"
              onClick={() => setSelectedAgent(null)}
            >
              Clear filter
            </Button>
          </div>
        ) : null}

        <TokenSystemDiagram
          composition={composition}
          onDrill={(k) => setDrill((cur) => (cur === k ? null : k))}
          activeDrill={drill}
          selectedAgent={selectedAgent}
          onSelectAgent={setSelectedAgent}
        />

        {drill ? (
          <div className="mt-6">
            <DrillPanel
              title={drillInfo[drill].title}
              rows={drillInfo[drill].rows}
              total={drillInfo[drill].total}
              onClose={() => setDrill(null)}
            />
          </div>
        ) : null}
      </div>
    </Card>
  );
};
