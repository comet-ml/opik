export const ONBOARDING_STEPS = {
  ROLE: 1,
  AI_JOURNEY: 2,
  START_PREFERENCE: 3,
} as const;

export const STEP_IDENTIFIERS = {
  [ONBOARDING_STEPS.ROLE]: "role",
  [ONBOARDING_STEPS.AI_JOURNEY]: "ai_journey",
  [ONBOARDING_STEPS.START_PREFERENCE]: "start_preference",
} as const;
