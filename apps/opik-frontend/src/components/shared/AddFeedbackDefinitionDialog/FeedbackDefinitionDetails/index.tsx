import React from "react";

import {
  CreateFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";

import CategoricalFeedbackDefinitionDetails from "./CategoricalFeedbackDefinitionDetails";
import NumericalFeedbackDefinitionDetails from "./NumericalFeedbackDefinitionDetails";

type FeedbackDefinitionDetailsProps = {
  onChange: (details: CreateFeedbackDefinition["details"]) => void;
  type: CreateFeedbackDefinition["type"];
};

const FeedbackDefinitionDetails: React.FunctionComponent<
  FeedbackDefinitionDetailsProps
> = ({ onChange, type }) => {
  if (type === FEEDBACK_DEFINITION_TYPE.categorical) {
    return <CategoricalFeedbackDefinitionDetails onChange={onChange} />;
  }

  if (type === FEEDBACK_DEFINITION_TYPE.numerical) {
    return <NumericalFeedbackDefinitionDetails onChange={onChange} />;
  }

  return null;
};

export default FeedbackDefinitionDetails;
