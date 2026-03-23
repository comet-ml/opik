import React from "react";
import OnboardingStep from "../OnboardingStep";

const options = [
  "Just getting started",
  "Exploring ideas and experimenting",
  "Testing a working prototype",
  "Running a live application in production",
];

const AIJourney: React.FC = () => {
  return (
    <OnboardingStep>
      <OnboardingStep.BackButton />
      <OnboardingStep.Title>
        Where are you in your AI journey?
      </OnboardingStep.Title>

      <OnboardingStep.AnswerList>
        {options.map((option) => (
          <OnboardingStep.AnswerButton key={option} option={option} />
        ))}
      </OnboardingStep.AnswerList>

      <OnboardingStep.Skip />
    </OnboardingStep>
  );
};

export default AIJourney;
