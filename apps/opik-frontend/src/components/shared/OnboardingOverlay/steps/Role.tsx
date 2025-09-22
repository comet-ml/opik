import React from "react";
import OnboardingStep from "../OnboardingStep";

const options = [
  "Software Developer",
  "ML Engineer / Data Scientist",
  "Product Manager",
  "Other",
];

const Role: React.FC = () => {
  return (
    <OnboardingStep className="mt-16">
      <OnboardingStep.Title>
        How would you describe your role?
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

export default Role;
