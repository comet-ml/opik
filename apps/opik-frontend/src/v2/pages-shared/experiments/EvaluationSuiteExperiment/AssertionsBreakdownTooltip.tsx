import React, {
  type ReactNode,
  useCallback,
  useMemo,
  useRef,
  useState,
} from "react";
import * as AccordionPrimitive from "@radix-ui/react-accordion";
import { ChevronDown, CircleCheck, CircleX } from "lucide-react";

import { cn } from "@/lib/utils";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Accordion, AccordionContent, AccordionItem } from "@/ui/accordion";
import { AssertionResult } from "@/types/datasets";

type Side = "top" | "bottom" | "left" | "right";

// max-h-96 (384px) + sideOffset (4px) + collisionPadding (16px)
const MAX_TOOLTIP_HEIGHT = 404;

type AssertionsBreakdownTooltipProps = {
  children: ReactNode;
  assertionsByRun: AssertionResult[][];
};

export const AssertionsBreakdownTooltip: React.FC<
  AssertionsBreakdownTooltipProps
> = ({ children, assertionsByRun }) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLSpanElement>(null);
  const [preferredSide, setPreferredSide] = useState<Side>("bottom");

  // Prevent side flipping while accordion items expand/collapse by making the opposite
  // side appear to always overflow. Horizontal padding stays at 16 so the tooltip still
  // nudges left/right to stay inside the viewport.
  const activeCollisionPadding = useMemo(() => {
    const LOCK_PADDING = 99999;
    return {
      top: preferredSide === "bottom" ? LOCK_PADDING : 16,
      bottom: preferredSide === "top" ? LOCK_PADDING : 16,
      left: preferredSide === "right" ? LOCK_PADDING : 16,
      right: preferredSide === "left" ? LOCK_PADDING : 16,
    };
  }, [preferredSide]);

  const scrollToRun = useCallback((idx: number) => {
    // Wait for the 0.2s accordion animation to settle before measuring positions,
    // otherwise the collapsing-run layout shift causes the scroll to land mid-content.
    setTimeout(() => {
      const container = scrollContainerRef.current;
      if (!container) return;
      const item = container.querySelector(
        `[data-run-idx="${idx}"]`,
      ) as HTMLElement | null;
      if (item) {
        const containerTop = container.getBoundingClientRect().top;
        const itemTop = item.getBoundingClientRect().top;
        container.scrollTop += itemTop - containerTop;
      }
    }, 200);
  }, []);

  const scrollToFirstFailedAssertion = useCallback((runIdx: number) => {
    // Double RAF: first positions the run, second targets the failed assertion
    // after layout is stable (defaultValue means no animation delay needed)
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const container = scrollContainerRef.current;
        if (!container) return;
        const runItem = container.querySelector(
          `[data-run-idx="${runIdx}"]`,
        ) as HTMLElement | null;
        if (!runItem) return;
        const firstFailed = runItem.querySelector(
          '[data-assertion-passed="false"]',
        ) as HTMLElement | null;
        // When all assertions pass there is nothing to scroll to — leave the
        // container at the top so Run 1 content is not clipped.
        if (!firstFailed) return;
        // Offset by the sticky header height so the failed assertion lands
        // below the header rather than hidden behind it.
        const headerHeight =
          (
            runItem.firstElementChild as HTMLElement | null
          )?.getBoundingClientRect().height ?? 0;
        const containerTop = container.getBoundingClientRect().top;
        const targetTop = firstFailed.getBoundingClientRect().top;
        container.scrollTop += targetTop - containerTop - headerHeight;
      });
    });
  }, []);

  const defaultOpenIdx = useMemo(
    () => assertionsByRun.findIndex((run) => run.some((a) => !a.passed)),
    [assertionsByRun],
  );

  const handleOpenChange = useCallback(
    (open: boolean) => {
      if (open) {
        // Compute side from trigger position — no async DOM sync needed
        if (triggerRef.current) {
          const { bottom } = triggerRef.current.getBoundingClientRect();
          setPreferredSide(
            window.innerHeight - bottom < MAX_TOOLTIP_HEIGHT ? "top" : "bottom",
          );
        }
        scrollToFirstFailedAssertion(defaultOpenIdx >= 0 ? defaultOpenIdx : 0);
      }
      // No reset on close: resetting collisionPadding mid-animation causes Radix
      // to re-evaluate position and flip the tooltip, producing a visible jump.
      // preferredSide is always recomputed fresh on the next open.
    },
    [defaultOpenIdx, scrollToFirstFailedAssertion],
  );

  const handleValueChange = useCallback(
    (value: string) => {
      const idx = parseInt(value.replace("run-", ""), 10);
      if (!isNaN(idx)) scrollToRun(idx);
    },
    [scrollToRun],
  );

  if (assertionsByRun.length === 0 || assertionsByRun[0].length === 0) {
    return <>{children}</>;
  }

  const defaultValue = `run-${defaultOpenIdx >= 0 ? defaultOpenIdx : 0}`;

  return (
    <HoverCard openDelay={200} closeDelay={500} onOpenChange={handleOpenChange}>
      <HoverCardTrigger asChild>
        <span ref={triggerRef}>{children}</span>
      </HoverCardTrigger>
      <HoverCardContent
        side={preferredSide}
        align="start"
        collisionPadding={activeCollisionPadding}
        className="w-[30rem] overflow-hidden p-0"
        onClick={(e) => e.stopPropagation()}
      >
        <Accordion
          type="single"
          defaultValue={defaultValue}
          collapsible
          onValueChange={handleValueChange}
        >
          <div ref={scrollContainerRef} className="max-h-96 overflow-y-auto">
            {assertionsByRun.map((run, runIdx) => {
              const passedCount = run.filter((a) => a.passed).length;
              const allPassed = passedCount === run.length;
              return (
                <AccordionItem
                  key={runIdx}
                  value={`run-${runIdx}`}
                  className="last:border-b-0"
                  data-run-idx={runIdx}
                >
                  <AccordionPrimitive.Header className="sticky top-0 z-10 flex bg-background">
                    <AccordionPrimitive.Trigger className="flex flex-1 items-center justify-between px-3 py-2 outline-none focus-visible:ring-1 focus-visible:ring-ring [&[data-state=open]>svg:last-child]:rotate-180">
                      <div className="flex items-center gap-2">
                        <span className="comet-body-xs-accented text-foreground">
                          Run {runIdx + 1}
                        </span>
                        <div
                          className={cn(
                            "inline-flex h-5 items-center gap-1 rounded-sm px-2 font-mono text-xs font-normal",
                            allPassed
                              ? "bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]"
                              : "bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
                          )}
                        >
                          {allPassed ? (
                            <CircleCheck className="size-3 shrink-0" />
                          ) : (
                            <CircleX className="size-3 shrink-0" />
                          )}
                          {passedCount}/{run.length} assertions passed
                        </div>
                      </div>
                      <ChevronDown className="size-3 shrink-0 transition-transform duration-200" />
                    </AccordionPrimitive.Trigger>
                  </AccordionPrimitive.Header>
                  <AccordionContent className="p-0">
                    {run.map((assertion, aIdx) => (
                      <div
                        key={aIdx}
                        className="flex gap-2 px-3 py-2"
                        data-assertion-passed={String(assertion.passed)}
                      >
                        {assertion.passed ? (
                          <CircleCheck className="mt-0.5 size-3.5 shrink-0 text-success" />
                        ) : (
                          <CircleX className="mt-0.5 size-3.5 shrink-0 text-destructive" />
                        )}
                        <div className="flex flex-col gap-0.5">
                          <p className="comet-body-xs-accented text-foreground">
                            {assertion.value}
                          </p>
                          {assertion.reason && (
                            <p className="comet-body-xs text-muted-slate">
                              {assertion.reason}
                            </p>
                          )}
                        </div>
                      </div>
                    ))}
                  </AccordionContent>
                </AccordionItem>
              );
            })}
          </div>
        </Accordion>
      </HoverCardContent>
    </HoverCard>
  );
};

export default AssertionsBreakdownTooltip;
