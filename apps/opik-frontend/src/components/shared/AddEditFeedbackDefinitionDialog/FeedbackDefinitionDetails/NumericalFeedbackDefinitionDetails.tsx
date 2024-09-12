import React, { useEffect, useState } from "react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NumericalFeedbackDefinition } from "@/types/feedback-definitions";

type NumericalFeedbackDefinitionDetailsProps = {
  onChange: (details: NumericalFeedbackDefinition["details"]) => void;
  details?: NumericalFeedbackDefinition["details"];
};

const NumericalFeedbackDefinitionDetails: React.FunctionComponent<
  NumericalFeedbackDefinitionDetailsProps
> = ({ onChange, details }) => {
  const [numericalDetails, setNumericalDetails] = useState<
    NumericalFeedbackDefinition["details"]
  >(details ?? { min: 0, max: 1 });

  useEffect(() => {
    onChange(numericalDetails);
  }, [numericalDetails, onChange]);

  return (
    <>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionNumericalMin">Min</Label>
        <Input
          id="feedbackDefinitionNumericalMin"
          placeholder="Min"
          value={numericalDetails.min}
          type="number"
          onChange={(event) =>
            setNumericalDetails((details) => ({
              ...details,
              min: Number(event.target.value),
            }))
          }
        />
      </div>

      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionNumericalMax">Max</Label>
        <Input
          id="feedbackDefinitionNumericalMax"
          placeholder="Max"
          value={numericalDetails.max}
          type="number"
          onChange={(event) =>
            setNumericalDetails((details) => ({
              ...details,
              max: Number(event.target.value),
            }))
          }
        />
      </div>
    </>
  );
};

export default NumericalFeedbackDefinitionDetails;
