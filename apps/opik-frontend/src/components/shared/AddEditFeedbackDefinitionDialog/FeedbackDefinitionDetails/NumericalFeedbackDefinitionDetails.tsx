import React, { useEffect, useState } from "react";
import isNumber from "lodash/isNumber";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NumericalFeedbackDefinition } from "@/types/feedback-definitions";

const INVALID_DETAILS_VALUE: NumericalFeedbackDefinition["details"] = {
  min: 0,
  max: 0,
};

type NumericalFeedbackDefinitionDetails = {
  min: number | "";
  max: number | "";
};

type NumericalFeedbackDefinitionDetailsProps = {
  onChange: (details: NumericalFeedbackDefinition["details"]) => void;
  details?: NumericalFeedbackDefinition["details"];
};

const NumericalFeedbackDefinitionDetails: React.FunctionComponent<
  NumericalFeedbackDefinitionDetailsProps
> = ({ onChange, details }) => {
  const [numericalDetails, setNumericalDetails] =
    useState<NumericalFeedbackDefinitionDetails>(details ?? { min: 0, max: 1 });

  useEffect(() => {
    const isValid =
      isNumber(numericalDetails.min) && isNumber(numericalDetails.max);

    onChange(
      isValid
        ? (numericalDetails as NumericalFeedbackDefinition["details"])
        : INVALID_DETAILS_VALUE,
    );
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
          step="any"
          onChange={(event) =>
            setNumericalDetails((details) => ({
              ...details,
              min: event.target.value === "" ? "" : Number(event.target.value),
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
          step="any"
          onChange={(event) =>
            setNumericalDetails((details) => ({
              ...details,
              max: event.target.value === "" ? "" : Number(event.target.value),
            }))
          }
        />
      </div>
    </>
  );
};

export default NumericalFeedbackDefinitionDetails;
