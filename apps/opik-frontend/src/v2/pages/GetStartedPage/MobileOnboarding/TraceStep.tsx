import React from "react";
import {
  Clock,
  Brain,
  Coins,
  Hash,
  AlertTriangle,
  InspectionPanel,
  MessageCircle,
  Link,
  Hammer,
} from "lucide-react";
import { TraceIllustration } from "./illustrations";

interface SpanMeta {
  duration: string;
  model?: string;
  cost?: string;
  tokens?: string;
  tool?: string;
}

interface SpanNode {
  name: string;
  icon: React.ElementType;
  tagColorClass: string;
  darkIcon?: boolean;
  depth: number;
  metadata?: SpanMeta;
  slow?: boolean;
}

const INDENT_PX = 14;
const ICON_SIZE = 14;
const ROW_GAP = 3;

const SPAN_BASE_DELAY = 250;
const SPAN_STAGGER = 100;
const HIGHLIGHT_DELAY = 1500;

const SPANS: SpanNode[] = [
  {
    name: "support_agent",
    icon: InspectionPanel,
    tagColorClass: "bg-accent-purple",
    depth: 0,
    metadata: {
      duration: "3.8s",
      model: "gpt-5.4",
      cost: "$0.03",
      tokens: "720",
    },
  },
  {
    name: "classify_intent",
    icon: MessageCircle,
    tagColorClass: "bg-accent-blue",
    darkIcon: true,
    depth: 1,
    metadata: {
      duration: "360ms",
      model: "gpt-5.4",
      cost: "$0.001",
      tokens: "48",
    },
  },
  {
    name: "search_knowledge",
    icon: Hammer,
    tagColorClass: "bg-accent-magenta",
    depth: 1,
    metadata: { duration: "1.25s", tool: "opik_docs_search" },
    slow: true,
  },
  {
    name: "retrieve_documentation_pages",
    icon: Link,
    tagColorClass: "bg-accent-green",
    darkIcon: true,
    depth: 2,
    metadata: { duration: "680ms" },
  },
  {
    name: "final_answer",
    icon: MessageCircle,
    tagColorClass: "bg-accent-blue",
    darkIcon: true,
    depth: 1,
    metadata: {
      duration: "1.42s",
      model: "gpt-5.4",
      cost: "$0.028",
      tokens: "672",
    },
  },
];

function buildConnectorInfo(spans: SpanNode[]): boolean[][] {
  const info: boolean[][] = new Array(spans.length);
  const hasFollowing: boolean[] = [];

  for (let i = spans.length - 1; i >= 0; i--) {
    const node = spans[i];
    const depths: boolean[] = [];
    for (let d = 1; d <= node.depth; d++) {
      depths.push(hasFollowing[d] ?? false);
    }
    info[i] = depths;
    hasFollowing[node.depth] = true;
    for (let d = node.depth + 1; d < hasFollowing.length; d++) {
      hasFollowing[d] = false;
    }
  }
  return info;
}

const CONNECTOR_INFO = buildConnectorInfo(SPANS);

const delay = (ms: number) => ({ animationDelay: `${ms}ms` });

const MetaItem: React.FC<{ icon: React.ReactNode; value: string }> = ({
  icon,
  value,
}) => (
  <span className="flex items-center gap-0.5 text-[10px] leading-3 text-muted-slate">
    {icon} {value}
  </span>
);

const SpanRow: React.FC<{
  span: SpanNode;
  connectors: boolean[];
  hasChildren: boolean;
  isFirst: boolean;
  isLast: boolean;
  animationDelay: number;
}> = ({ span, connectors, hasChildren, isFirst, isLast, animationDelay }) => {
  const {
    name,
    icon: Icon,
    tagColorClass,
    darkIcon,
    metadata,
    slow,
    depth,
  } = span;
  const contentLeft = depth * INDENT_PX;
  const lineCenterOf = (x: number) => x + (ICON_SIZE - 1) / 2;
  const iconCenterY = 4 + 8;

  return (
    <div className="relative overflow-visible">
      {/* Connector lines */}
      {depth > 0 && (
        <div
          className="onboarding-fade-in absolute inset-0"
          style={{
            top: isFirst ? 0 : -ROW_GAP,
            bottom: isLast ? 0 : -ROW_GAP,
            ...delay(animationDelay),
          }}
        >
          {Array.from({ length: depth }, (_, i) => {
            const d = i + 1;
            const isOwnDepth = d === depth;
            const hasContinuation = connectors[i] ?? false;
            const lineX = lineCenterOf((d - 1) * INDENT_PX);
            const branchEndX = lineCenterOf(d * INDENT_PX);
            const branchTargetY = (isFirst ? 0 : ROW_GAP) + iconCenterY + 1;

            if (!isOwnDepth && !hasContinuation) return null;

            if (!isOwnDepth) {
              return (
                <div
                  key={d}
                  className="absolute inset-y-0 w-px bg-tree-line"
                  style={{ left: lineX }}
                />
              );
            }

            return (
              <React.Fragment key={d}>
                {hasContinuation && (
                  <div
                    className="absolute inset-y-0 w-px bg-tree-line"
                    style={{ left: lineX }}
                  />
                )}
                <div
                  className="absolute border-b border-l border-tree-line"
                  style={{
                    left: lineX,
                    top: hasContinuation ? branchTargetY - 6 : 0,
                    height: hasContinuation ? 6 : branchTargetY,
                    width: branchEndX - lineX,
                    borderBottomLeftRadius: 4,
                  }}
                />
              </React.Fragment>
            );
          })}
        </div>
      )}

      {hasChildren && (
        <div
          className="onboarding-fade-in pointer-events-none absolute w-px bg-tree-line"
          style={{
            left: lineCenterOf(contentLeft),
            top: iconCenterY + ICON_SIZE / 2,
            bottom: isLast ? 0 : -ROW_GAP,
            ...delay(animationDelay),
          }}
        />
      )}

      {/* Span content */}
      <div
        className="slide-fade-subtle relative flex flex-col gap-0.5 py-1 pr-2"
        style={{ paddingLeft: contentLeft, ...delay(animationDelay) }}
      >
        {slow && (
          <div
            className="slow-highlight-in absolute inset-0 rounded-md border border-transparent"
            style={delay(HIGHLIGHT_DELAY)}
          />
        )}
        <div className="flex h-4 items-center gap-1">
          <div
            className={`flex shrink-0 items-center justify-center rounded ${tagColorClass} size-[14px]`}
          >
            <Icon
              className={`size-2 ${
                darkIcon ? "dark:text-slate-900" : "text-white"
              }`}
            />
          </div>
          <span className="min-w-0 flex-1 truncate text-xs font-medium text-foreground">
            {name}
          </span>
          {slow && (
            <span
              className="onboarding-fade-in flex shrink-0 items-center gap-0.5 text-[10px] leading-none text-chart-yellow"
              style={delay(HIGHLIGHT_DELAY)}
            >
              <AlertTriangle className="size-2.5" /> Slow
            </span>
          )}
        </div>

        {metadata && (
          <div className="flex flex-wrap items-center gap-1.5 pl-[18px]">
            {metadata.duration && (
              <MetaItem
                icon={<Clock className="size-2.5" />}
                value={metadata.duration}
              />
            )}
            {metadata.model && (
              <MetaItem
                icon={<Brain className="size-2.5" />}
                value={metadata.model}
              />
            )}
            {metadata.tool && (
              <MetaItem
                icon={<Hammer className="size-2.5" />}
                value={metadata.tool}
              />
            )}
            {metadata.cost && (
              <MetaItem
                icon={<Coins className="size-2.5" />}
                value={metadata.cost}
              />
            )}
            {metadata.tokens && (
              <MetaItem
                icon={<Hash className="size-2.5" />}
                value={metadata.tokens}
              />
            )}
          </div>
        )}
      </div>
    </div>
  );
};

const TraceStep: React.FC = () => {
  return (
    <>
      <div className="slide-fade-right">
        <TraceIllustration />
      </div>

      <div className="flex flex-col gap-1.5 px-0.5">
        <h1 className="slide-fade-right text-lg font-medium text-foreground [animation-delay:75ms]">
          Every AI request becomes a trace
        </h1>
        <p className="slide-fade-right pb-2 text-sm text-muted-slate [animation-delay:150ms]">
          A trace captures every step your AI took to generate an answer.
          Here&apos;s the one you just sent.
        </p>
      </div>

      <div className="slide-fade-right overflow-hidden rounded-md border border-border bg-soft-background [animation-delay:200ms] dark:bg-accent-background">
        <div className="flex h-8 items-center justify-between border-b border-border px-3">
          <span className="text-xs font-medium leading-3 text-foreground">
            Demo trace
          </span>
          <span className="text-[10px] leading-3 text-muted-slate">
            5 spans
          </span>
        </div>

        <div className="relative flex flex-col gap-[3px] px-3 py-1">
          {SPANS.map((span, i) => {
            const nextSpan = SPANS[i + 1];
            const hasChildren =
              nextSpan !== undefined && nextSpan.depth > span.depth;
            return (
              <SpanRow
                key={span.name}
                span={span}
                connectors={CONNECTOR_INFO[i]}
                hasChildren={hasChildren}
                isFirst={i === 0}
                isLast={i === SPANS.length - 1}
                animationDelay={SPAN_BASE_DELAY + i * SPAN_STAGGER}
              />
            );
          })}
        </div>
      </div>
    </>
  );
};

export default TraceStep;
