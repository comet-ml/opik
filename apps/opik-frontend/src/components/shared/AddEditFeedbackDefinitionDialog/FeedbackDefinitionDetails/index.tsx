import React from "react";

import {
  CategoricalFeedbackDefinition,
  CreateFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
  NumericalFeedbackDefinition,
} from "@/types/feedback-definitions";

import CategoricalFeedbackDefinitionDetails from "./CategoricalFeedbackDefinitionDetails";
import NumericalFeedbackDefinitionDetails from "./NumericalFeedbackDefinitionDetails";

type FeedbackDefinitionDetailsProps = {
  onChange: (details: CreateFeedbackDefinition["details"]) => void;
  details?: CreateFeedbackDefinition["details"];
  type: CreateFeedbackDefinition["type"];
};

const FeedbackDefinitionDetails: React.FunctionComponent<
  FeedbackDefinitionDetailsProps
> = ({ onChange, details, type }) => {
  if (type === FEEDBACK_DEFINITION_TYPE.categorical) {
    return (
      <CategoricalFeedbackDefinitionDetails
        onChange={onChange}
        details={details as CategoricalFeedbackDefinition["details"]}
      />
    );
  }

  if (type === FEEDBACK_DEFINITION_TYPE.numerical) {
    return (
      <NumericalFeedbackDefinitionDetails
        onChange={onChange}
        details={details as NumericalFeedbackDefinition["details"]}
      />
    );
  }

  return null;
};

export default FeedbackDefinitionDetails;
