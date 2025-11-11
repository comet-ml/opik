import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation();
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
        <Label htmlFor="feedbackDefinitionNumericalMin">{t("configuration.feedbackDefinitions.dialog.min")}</Label>
        <Input
          id="feedbackDefinitionNumericalMin"
          placeholder={t("configuration.feedbackDefinitions.dialog.minPlaceholder")}
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
        <Label htmlFor="feedbackDefinitionNumericalMax">{t("configuration.feedbackDefinitions.dialog.max")}</Label>
        <Input
          id="feedbackDefinitionNumericalMax"
          placeholder={t("configuration.feedbackDefinitions.dialog.maxPlaceholder")}
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
