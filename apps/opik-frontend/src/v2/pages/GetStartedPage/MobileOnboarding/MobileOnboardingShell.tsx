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
  const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")
    .matches;
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
  const [panelAnim, setPanelAnim] = useState<
    { seq: number; mode: "in" | "out" }[]
  >(() => Array.from({ length: totalSteps }, () => ({ seq: 0, mode: "in" })));

  // On step change: run the out/in animations and, for button navigation,
  // scroll the panel into view. Single effect so the fromScroll flag is read
  // and consumed in one place.
  useEffect(() => {
    if (prevStep.current === step) return;
    const from = prevStep.current;
    prevStep.current = step;

    setPanelAnim((arr) =>
      arr.map((p, i) =>
        i === step - 1
          ? { seq: p.seq + 1, mode: "in" }
          : i === from - 1
            ? { seq: p.seq + 1, mode: "out" }
            : p,
      ),
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
          <div className="w-full shrink-0 snap-center snap-always overflow-y-auto overflow-x-hidden px-5 py-3">
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
