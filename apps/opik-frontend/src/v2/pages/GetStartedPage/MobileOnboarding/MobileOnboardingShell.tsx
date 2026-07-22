import React from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import Logo from "@/shared/Logo/Logo";
import { Button } from "@/ui/button";

interface MobileOnboardingShellProps {
  step: number;
  totalSteps: number;
  onBack?: () => void;
  onNext: () => void;
  nextLabel: string;
  nextVariant?: "default" | "outline";
  children: React.ReactNode;
}

const MobileOnboardingShell: React.FC<MobileOnboardingShellProps> = ({
  step,
  totalSteps,
  onBack,
  onNext,
  nextLabel,
  nextVariant = "default",
  children,
}) => {
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

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-3">
        <div className="flex flex-col gap-3">{children}</div>
      </div>

      <div className="flex items-center gap-2 p-5">
        {onBack && (
          <Button
            variant="outline"
            size="sm"
            onClick={onBack}
            className="w-20 gap-1.5"
          >
            <ArrowLeft className="size-3.5" />
            Back
          </Button>
        )}
        <Button
          variant={nextVariant}
          size="sm"
          onClick={onNext}
          className="flex-1 gap-1.5"
        >
          {nextLabel}
          <ArrowRight className="size-3.5" />
        </Button>
      </div>
    </div>
  );
};

export default MobileOnboardingShell;
