import React from "react";

import {
  BooleanFeedbackDefinition,
  CategoricalFeedbackDefinition,
  CreateFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
  NumericalFeedbackDefinition,
} from "@/types/feedback-definitions";

import BooleanFeedbackDefinitionDetails from "./BooleanFeedbackDefinitionDetails";
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

  if (type === FEEDBACK_DEFINITION_TYPE.boolean) {
    return (
      <BooleanFeedbackDefinitionDetails
        onChange={onChange}
        details={details as BooleanFeedbackDefinition["details"]}
      />
    );
  }

  return null;
};

export default FeedbackDefinitionDetails;
