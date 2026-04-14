import React from "react";
import { CircleCheck } from "lucide-react";

import { Button } from "@/ui/button";

type SuiteCreatedSuccessProps = {
  suiteName: string;
  onGoToSuite: () => void;
  onCreateAnother: () => void;
};

const SuiteCreatedSuccess: React.FC<SuiteCreatedSuccessProps> = ({
  suiteName,
  onGoToSuite,
  onCreateAnother,
}) => {
  return (
    <div className="flex flex-1 flex-col items-center gap-4 pt-24">
      <div className="flex size-16 items-center justify-center rounded-full bg-emerald-50">
        <CircleCheck className="size-8 text-emerald-600" />
      </div>
      <div className="text-center">
        <h3 className="comet-body-s-accented mb-1">Test suite created!</h3>
        <p className="comet-body-xs text-light-slate">
          Your test suite &quot;{suiteName}&quot; has been successfully created.
        </p>
      </div>
      <div className="flex w-48 flex-col gap-2">
        <Button onClick={onGoToSuite} size="sm">
          Go to suite
        </Button>
        <Button variant="outline" size="sm" onClick={onCreateAnother}>
          Create another
        </Button>
      </div>
    </div>
  );
};

export default SuiteCreatedSuccess;
