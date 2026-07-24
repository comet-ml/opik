import React from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import Logo from "@/shared/Logo/Logo";
import { Button } from "@/ui/button";
import { useSwipeableSteps } from "./useSwipeableSteps";

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
  const { scrollerProps, getPanelProps, getContentProps } = useSwipeableSteps({
    step,
    totalSteps,
    onStepChange,
  });

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
        {...scrollerProps}
        className="flex min-h-0 flex-1 snap-x snap-mandatory overflow-x-auto overflow-y-hidden overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {React.Children.map(children, (panel, i) => {
          const { key, reversed } = getContentProps(i);
          return (
            <div
              {...getPanelProps(i)}
              className="w-full shrink-0 snap-center snap-always overflow-y-auto overflow-x-hidden px-5 py-3"
            >
              <div
                key={key}
                className={`flex flex-col gap-3 ${
                  reversed ? "comet-onboarding-anim-reverse" : ""
                }`}
              >
                {panel}
              </div>
            </div>
          );
        })}
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
