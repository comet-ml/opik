import React, { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import Logo from "@/shared/Logo/Logo";
import { Button } from "@/ui/button";

interface MobileOnboardingShellProps {
  step: number;
  totalSteps: number;
  onBack?: () => void;
  onNext: () => void;
  /** Called when the user swipes to another step. */
  onStepChange: (step: number) => void;
  nextLabel: string;
  nextVariant?: "default" | "outline";
  /** One panel per step; all stay mounted so swiping is native scrolling. */
  children: React.ReactNode;
}

const clampStep = (value: number, totalSteps: number) =>
  Math.min(totalSteps, Math.max(1, value));

interface PanelAnim {
  /** Remount counter — bumping it replays the panel's entrance animation. */
  seq: number;
  mode: "in" | "out";
}

/** Panel animation state for a step change: the incoming panel replays its
 * entrance, the outgoing one plays it in reverse, and any straggler still
 * marked "out" (a rapid earlier navigation cancelled its pending restore)
 * is brought back — offscreen, so its forward replay is invisible. */
const panelAnimForStepChange = (
  arr: PanelAnim[],
  incomingIndex: number,
  outgoingIndex: number,
): PanelAnim[] =>
  arr.map((p, i) => {
    if (i === incomingIndex) return { seq: p.seq + 1, mode: "in" };
    if (i === outgoingIndex) return { seq: p.seq + 1, mode: "out" };
    if (p.mode === "out") return { seq: p.seq + 1, mode: "in" };
    return p;
  });

// The outgoing panel is restored once its reverse cascade finishes, detected
// via animationend debounce (which adapts to whatever delays the steps use).
// The fallback cap only fires when no animation events arrive at all (e.g.
// prefers-reduced-motion disables the animations, leaving content visible).
const OUT_RESET_DEBOUNCE_MS = 300;
const OUT_RESET_FALLBACK_MS = 2500;

/** Derives the 1-indexed step the scroller is currently closest to. */
const stepFromScroll = (el: HTMLElement, totalSteps: number) =>
  clampStep(Math.round(el.scrollLeft / el.clientWidth) + 1, totalSteps);

/**
 * Smooth-scrolls the panel for `step` into view (instant under
 * prefers-reduced-motion). Returns the target scrollLeft, or null when the
 * scroller is already there.
 */
const scrollPanelIntoView = (el: HTMLElement, step: number): number | null => {
  const left = (step - 1) * el.clientWidth;
  if (Math.abs(el.scrollLeft - left) < 2) return null;
  const reduceMotion =
    window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false;
  el.scrollTo({ left, behavior: reduceMotion ? "auto" : "smooth" });
  return left;
};

const MobileOnboardingShell: React.FC<MobileOnboardingShellProps> = ({
  step,
  totalSteps,
  onBack,
  onNext,
  onStepChange,
  nextLabel,
  nextVariant = "default",
  children,
}) => {
  const scrollerRef = useRef<HTMLDivElement>(null);
  // Set when a step change came from the user's own swipe — the scroller is
  // already at (or snapping to) the right place, so don't scroll it again.
  const fromScroll = useRef(false);
  // While a button-driven smooth scroll is in flight, scroll events must not
  // sync state (early frames still round to the old step and would flip the
  // progress bar back and forth). Holds the target position until reached.
  const targetLeft = useRef<number | null>(null);

  // Per-panel animation state. On every step change (swipe or buttons) the
  // outgoing panel's content remounts in `comet-onboarding-anim-reverse`,
  // replaying its staggered slide-fade entrance BACKWARDS (elements cascade
  // out), while the incoming panel remounts normally and staggers in.
  const prevStep = useRef(step);
  const outResetTimer = useRef<ReturnType<typeof setTimeout>>();
  const [panelAnim, setPanelAnim] = useState<PanelAnim[]>(() =>
    Array.from({ length: totalSteps }, () => ({ seq: 0, mode: "in" })),
  );

  // On step change: run the out/in animations and, for button navigation,
  // scroll the panel into view. Single effect so the fromScroll flag is read
  // and consumed in one place.
  useEffect(() => {
    if (prevStep.current === step) return;
    const from = prevStep.current;
    prevStep.current = step;

    setPanelAnim((arr) => panelAnimForStepChange(arr, step - 1, from - 1));

    // The reversed cascade parks the departed panel at its hidden first
    // frame (fill-mode: both). Once it finishes — detected by animationend
    // debounce below, with this timer as a no-events fallback — restore the
    // panel to its settled visible state so peeking back at it mid-swipe
    // never reveals a blank page.
    clearTimeout(outResetTimer.current);
    outResetTimer.current = setTimeout(
      () => restoreOutPanel(from - 1),
      OUT_RESET_FALLBACK_MS,
    );

    const el = scrollerRef.current;
    if (!el) return;
    if (fromScroll.current) {
      // User swiped — the scroller is already there; just the animations.
      fromScroll.current = false;
      return;
    }
    targetLeft.current = scrollPanelIntoView(el, step);
  }, [step]);

  useEffect(() => () => clearTimeout(outResetTimer.current), []);

  // Restores a departed panel to its settled visible state after its reverse
  // cascade. Guarded on mode so a late timer can't remount an active panel.
  const restoreOutPanel = (panelIndex: number) => {
    setPanelAnim((arr) =>
      arr.map((p, i) =>
        i === panelIndex && p.mode === "out"
          ? { seq: p.seq + 1, mode: "in" }
          : p,
      ),
    );
  };

  // Each animationend from the outgoing panel pushes the debounce forward;
  // when the events stop, the cascade is done and the panel can be restored.
  const handlePanelAnimationEnd = (panelIndex: number) => {
    clearTimeout(outResetTimer.current);
    outResetTimer.current = setTimeout(
      () => restoreOutPanel(panelIndex),
      OUT_RESET_DEBOUNCE_MS,
    );
  };

  // Swiping is plain native horizontal scrolling with snap points; we only
  // observe it to keep the step state (progress bar, labels) in sync.
  const handleScroll = () => {
    const el = scrollerRef.current;
    if (!el || !el.clientWidth) return;

    if (targetLeft.current !== null) {
      // Programmatic scroll in flight — just watch for arrival.
      if (Math.abs(el.scrollLeft - targetLeft.current) < 2) {
        targetLeft.current = null;
      }
    } else {
      const index = stepFromScroll(el, totalSteps);
      if (index !== step) {
        fromScroll.current = true;
        onStepChange(index);
      }
    }
  };

  // Any user interaction (touch, trackpad/wheel, pointer) interrupting a
  // button-driven scroll returns control to the user immediately, so step
  // syncing is never left suppressed by an unreached target position.
  const handleUserScrollStart = () => {
    targetLeft.current = null;
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-background">
      <div className="flex flex-col gap-3 px-5 pb-2 pt-5">
        <div className="flex items-center justify-between px-px">
          <Logo expanded />
          <span className="text-xs text-light-slate">
            Step {step} of {totalSteps}
          </span>
        </div>

        <div className="flex gap-1.5">
          {Array.from({ length: totalSteps }, (_, i) => (
            <div
              key={i}
              className={`h-1.5 flex-1 rounded-md ${
                i + 1 === step
                  ? "bg-primary"
                  : i + 1 < step
                    ? "bg-primary/30"
                    : "bg-primary-100"
              }`}
            />
          ))}
        </div>
      </div>

      <div
        ref={scrollerRef}
        onScroll={handleScroll}
        onTouchStart={handleUserScrollStart}
        onWheel={handleUserScrollStart}
        onPointerDown={handleUserScrollStart}
        className="flex min-h-0 flex-1 snap-x snap-mandatory overflow-x-auto overflow-y-hidden overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {React.Children.map(children, (panel, i) => (
          <div
            // Offscreen panels are inert: removed from the tab order, the
            // accessibility tree and pointer events, so keyboard/screen-reader
            // users can't reach controls of steps they're not on. Set
            // imperatively — React 18 has no boolean `inert` prop support.
            // Browsers without inert fall back to aria-hidden plus disabled
            // pointer events (tab order can't be fully fixed without inert).
            ref={(node) => {
              if (!node) return;
              const inactive = i + 1 !== step;
              if ("inert" in node) {
                node.inert = inactive;
              } else if (inactive) {
                (node as HTMLElement).setAttribute("aria-hidden", "true");
                (node as HTMLElement).style.pointerEvents = "none";
              } else {
                (node as HTMLElement).removeAttribute("aria-hidden");
                (node as HTMLElement).style.pointerEvents = "";
              }
            }}
            onAnimationEnd={
              panelAnim[i]?.mode === "out"
                ? () => handlePanelAnimationEnd(i)
                : undefined
            }
            className="w-full shrink-0 snap-center snap-always overflow-y-auto overflow-x-hidden px-5 py-3"
          >
            <div
              key={panelAnim[i]?.seq ?? 0}
              className={`flex flex-col gap-3 ${
                panelAnim[i]?.mode === "out"
                  ? "comet-onboarding-anim-reverse"
                  : ""
              }`}
            >
              {panel}
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2 p-5">
        {onBack && (
          <Button
            variant="outline"
            size="lg"
            onClick={onBack}
            className="w-24 gap-1.5"
          >
            <ArrowLeft className="size-4" />
            Back
          </Button>
        )}
        <Button
          variant={nextVariant}
          size="lg"
          onClick={onNext}
          className="flex-1 gap-1.5"
        >
          {nextLabel}
          <ArrowRight className="size-4" />
        </Button>
      </div>
    </div>
  );
};

export default MobileOnboardingShell;
