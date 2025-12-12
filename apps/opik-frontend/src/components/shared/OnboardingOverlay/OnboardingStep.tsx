import React from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { ChevronLeft, ChevronsRight } from "lucide-react";
import { useOnboarding } from "./OnboardingOverlayContext";
import kebabCase from "lodash/kebabCase";

type OnboardingStepProps = {
  className?: string;
  children: React.ReactNode;
};

type TitleProps = {
  children: React.ReactNode;
};

type AnswerCardProps = {
  option: string;
};

type AnswerButtonProps = {
  option: string;
};

type AnswerListProps = {
  children: React.ReactNode;
  className?: string;
};

type OnboardingStepComponents = {
  Title: typeof Title;
  AnswerCard: typeof AnswerCard;
  AnswerList: typeof AnswerList;
  AnswerButton: typeof AnswerButton;
  Skip: typeof Skip;
  BackButton: typeof BackButton;
  StartExploring: typeof StartExploring;
};

const OnboardingStep: OnboardingStepComponents &
  React.FC<OnboardingStepProps> = ({ className, children }) => {
  return (
    <div
      className={cn(
        "flex flex-col flex-1 items-start w-full max-w-[600px] space-y-6 px-10",
        className,
      )}
    >
      {children}
    </div>
  );
};

const Title: React.FC<TitleProps> = ({ children }) => {
  return <h1 className="comet-title-xl">{children}</h1>;
};

const AnswerCard: React.FC<AnswerCardProps> = ({ option }) => {
  const [title, description] = option.split(" – ");

  const { handleAnswer, currentStep } = useOnboarding();

  const kebabCaseTitle = title ? kebabCase(title) : "";
  const stepIdentifier = currentStep || "unknown";

  return (
    <Button
      variant="outline"
      onClick={() => handleAnswer(option)}
      className="flex min-h-[100px] w-full min-w-0 flex-col items-start whitespace-normal p-6 text-left"
      id={`onboarding-step-${stepIdentifier}-answer-card-${kebabCaseTitle}`}
      data-fs-element={`onboarding-step-${stepIdentifier}-answer-card-${kebabCaseTitle}`}
    >
      <span className="comet-body-s-accented">{title}</span>
      <span className="comet-body-xs text-muted-foreground">{description}</span>
    </Button>
  );
};

const AnswerList: React.FC<AnswerListProps> = ({ children, className }) => (
  <div className={cn("flex flex-col items-start space-y-3 pt-2", className)}>
    {children}
  </div>
);

const AnswerButton: React.FC<AnswerButtonProps> = ({ option }) => {
  const { handleAnswer, currentStep } = useOnboarding();

  const kebabCaseOption = kebabCase(option);
  const stepIdentifier = currentStep || "unknown";

  return (
    <Button
      variant="outline"
      size="default"
      onClick={() => handleAnswer(option)}
      className="h-auto justify-center whitespace-normal bg-white px-4 py-2.5 text-left dark:bg-soft-background hover:dark:bg-primary-foreground"
      id={`onboarding-step-${stepIdentifier}-answer-${kebabCaseOption}`}
      data-fs-element={`onboarding-step-${stepIdentifier}-answer-${kebabCaseOption}`}
    >
      {option}
    </Button>
  );
};

const Skip: React.FC = () => {
  const { handleSkip, currentStep } = useOnboarding();
  const stepIdentifier = currentStep || "unknown";

  return (
    <Button
      variant="link"
      className="pl-0 text-foreground"
      onClick={handleSkip}
      id={`onboarding-step-${stepIdentifier}-skip`}
      data-fs-element={`onboarding-step-${stepIdentifier}-skip`}
    >
      Skip
      <ChevronsRight className="ml-1 size-4" />
    </Button>
  );
};

const StartExploring: React.FC = () => {
  const { handleSkip, currentStep } = useOnboarding();
  const stepIdentifier = currentStep || "unknown";

  return (
    <Button
      variant="link"
      className="pl-0 text-foreground"
      onClick={handleSkip}
      id={`onboarding-step-${stepIdentifier}-start-exploring`}
      data-fs-element={`onboarding-step-${stepIdentifier}-start-exploring`}
    >
      Start exploring Opik
      <ChevronsRight className="ml-1 size-4" />
    </Button>
  );
};

const BackButton: React.FC = () => {
  const { handleBack, currentStep } = useOnboarding();
  const stepIdentifier = currentStep || "unknown";

  return (
    <div className="flex justify-start">
      <Button
        variant="link"
        className="pl-0 text-foreground"
        onClick={handleBack}
        id={`onboarding-step-${stepIdentifier}-back`}
        data-fs-element={`onboarding-step-${stepIdentifier}-back`}
      >
        <ChevronLeft className="mr-1 size-4" />
        Back
      </Button>
    </div>
  );
};

OnboardingStep.Title = Title;
OnboardingStep.AnswerCard = AnswerCard;
OnboardingStep.AnswerList = AnswerList;
OnboardingStep.AnswerButton = AnswerButton;
OnboardingStep.Skip = Skip;
OnboardingStep.BackButton = BackButton;
OnboardingStep.StartExploring = StartExploring;

export default OnboardingStep;
