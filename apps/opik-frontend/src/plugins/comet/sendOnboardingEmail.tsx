import api from "./api";

const sendOnboardingEmail = async (email: string): Promise<void> => {
  await api.post("/opik/onboarding/send-mobile-onboarding-email", { email });
};

export default sendOnboardingEmail;
