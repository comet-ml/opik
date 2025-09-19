import React from "react";
import { Button } from "@/components/ui/button";
import { ChevronLeft, ChevronRight } from "lucide-react";
import Loader from "@/components/shared/Loader/Loader";

type NavigationControlsProps = {
  isFirstItem: boolean;
  isLastItem: boolean;
  hasRequiredFeedback: boolean;
  isSubmitting: boolean;
  onPrevious: () => void;
  onSkip: () => void;
  onSubmitNext: () => void;
};

const NavigationControls: React.FC<NavigationControlsProps> = ({
  isFirstItem,
  isLastItem,
  hasRequiredFeedback,
  isSubmitting,
  onPrevious,
  onSkip,
  onSubmitNext,
}) => {
  return (
    <>
      <Button
        variant="outline"
        onClick={onPrevious}
        disabled={isFirstItem || isSubmitting}
      >
        <ChevronLeft className="mr-2 size-4" />
        Previous
      </Button>

      <Button variant="outline" onClick={onSkip} disabled={isSubmitting}>
        Skip
      </Button>
      <Button
        onClick={onSubmitNext}
        disabled={!hasRequiredFeedback || isSubmitting}
      >
        {isSubmitting ? (
          <>
            <Loader className="mr-2 size-4" />
            Submitting...
          </>
        ) : (
          <>
            {isLastItem ? "Submit & Complete" : "Submit + Next"}
            <ChevronRight className="ml-2 size-4" />
          </>
        )}
      </Button>
    </>
  );
};

export default NavigationControls;
