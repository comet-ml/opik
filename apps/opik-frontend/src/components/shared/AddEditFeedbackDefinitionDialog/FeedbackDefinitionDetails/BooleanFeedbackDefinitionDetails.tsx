import React, { useEffect, useState } from "react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { BooleanFeedbackDefinition } from "@/types/feedback-definitions";

const INVALID_DETAILS_VALUE: BooleanFeedbackDefinition["details"] = {
  true_label: "",
  false_label: "",
};

type BooleanFeedbackDefinitionDetailsProps = {
  onChange: (details: BooleanFeedbackDefinition["details"]) => void;
  details?: BooleanFeedbackDefinition["details"];
};

const BooleanFeedbackDefinitionDetails: React.FunctionComponent<
  BooleanFeedbackDefinitionDetailsProps
> = ({ onChange, details }) => {
  const [booleanDetails, setBooleanDetails] = useState<
    BooleanFeedbackDefinition["details"]
  >(details ?? { true_label: "Pass", false_label: "Fail" });

  useEffect(() => {
    const isValid =
      booleanDetails.true_label.trim() !== "" &&
      booleanDetails.false_label.trim() !== "";

    onChange(
      isValid
        ? (booleanDetails as BooleanFeedbackDefinition["details"])
        : INVALID_DETAILS_VALUE,
    );
  }, [booleanDetails, onChange]);

  return (
    <>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionBooleanTrueLabel">True label</Label>
        <Input
          id="feedbackDefinitionBooleanTrueLabel"
          placeholder="Pass"
          value={booleanDetails.true_label}
          onChange={(event) =>
            setBooleanDetails((details) => ({
              ...details,
              true_label: event.target.value,
            }))
          }
        />
      </div>

      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionBooleanFalseLabel">False label</Label>
        <Input
          id="feedbackDefinitionBooleanFalseLabel"
          placeholder="Fail"
          value={booleanDetails.false_label}
          onChange={(event) =>
            setBooleanDetails((details) => ({
              ...details,
              false_label: event.target.value,
            }))
          }
        />
      </div>
    </>
  );
};

export default BooleanFeedbackDefinitionDetails;
