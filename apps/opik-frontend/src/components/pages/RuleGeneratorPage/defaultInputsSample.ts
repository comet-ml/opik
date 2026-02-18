import { RuleGeneratorInput } from "./types";

type DefaultEntry = Pick<RuleGeneratorInput, "inputType" | "userInput">;

const DEFAULT_ENTRIES: DefaultEntry[] = [
  { inputType: "trace", userInput: "Any security issues?" },
  { inputType: "trace", userInput: "Secrets leaked?" },
  { inputType: "trace", userInput: "Policy followed?" },
  { inputType: "trace", userInput: "Toxic language?" },
  { inputType: "trace", userInput: "Hallucinations?" },
  { inputType: "trace", userInput: "Answered the question?" },
  { inputType: "trace", userInput: "Valid JSON?" },
  { inputType: "trace", userInput: "Professional tone?" },
  { inputType: "trace", userInput: "Correct format?" },
  { inputType: "trace", userInput: "Any internal info mentioned?" },
  {
    inputType: "trace",
    userInput: "Check for security risks in this response.",
  },
  {
    inputType: "trace",
    userInput: "Did the assistant expose any sensitive data?",
  },
  { inputType: "trace", userInput: "Does this violate safety policy?" },
  {
    inputType: "trace",
    userInput: "Is there any harmful or offensive language?",
  },
  { inputType: "trace", userInput: "Did the assistant make up any facts?" },
  { inputType: "trace", userInput: "Does the response stay on topic?" },
  { inputType: "trace", userInput: "Is the formatting correct?" },
  {
    inputType: "trace",
    userInput: "Is the tone appropriate for a workplace setting?",
  },
  {
    inputType: "trace",
    userInput: "Did the assistant refuse properly when needed?",
  },
  { inputType: "trace", userInput: "Does this look compliant?" },
  { inputType: "trace", userInput: "Anything sketchy in these responses?" },
  { inputType: "trace", userInput: "Did it spill anything it shouldn't have?" },
  { inputType: "trace", userInput: "Is the model BS-ing here?" },
  { inputType: "thread", userInput: "Did it dodge the question?" },
  { inputType: "trace", userInput: "Does this sound rude or aggressive?" },
  {
    inputType: "trace",
    userInput: "Is it being overly confident without evidence?",
  },
  { inputType: "thread", userInput: "Did it actually follow instructions?" },
  { inputType: "trace", userInput: "Is the structure what we asked for?" },
  { inputType: "trace", userInput: "Is it hallucinating?" },
  { inputType: "trace", userInput: "Does this feel safe to ship?" },
  {
    inputType: "trace",
    userInput:
      "Analyze the response for potential security vulnerabilities, including prompt injection exposure, data exfiltration, or unsafe execution instructions.",
  },
  {
    inputType: "trace",
    userInput:
      "Detect whether the assistant reveals sensitive information such as API keys, credentials, system prompts, or internal-only data.",
  },
  {
    inputType: "trace",
    userInput:
      "Evaluate compliance with safety and refusal policies for restricted or harmful requests.",
  },
  {
    inputType: "trace",
    userInput:
      "Identify any fabricated facts, unsupported claims, or unverifiable statements.",
  },
  {
    inputType: "trace",
    userInput:
      "Assess whether all factual claims are supported with appropriate citations when required.",
  },
  {
    inputType: "trace",
    userInput:
      "Verify that the output strictly adheres to the requested schema and formatting constraints.",
  },
  {
    inputType: "trace",
    userInput:
      "Determine whether the assistant's tone is professional, neutral, and free of bias.",
  },
  {
    inputType: "trace",
    userInput:
      "Check for toxic, abusive, discriminatory, or harassing language.",
  },
  {
    inputType: "trace",
    userInput:
      "Evaluate whether the assistant fully and directly addressed the user's request without unnecessary filler.",
  },
  {
    inputType: "trace",
    userInput:
      "Assess whether the response introduces speculative content presented as fact.",
  },
  { inputType: "trace", userInput: "Score factual accuracy from 1-5." },
  { inputType: "trace", userInput: "Score policy compliance from 1-5." },
  {
    inputType: "trace",
    userInput: "Classify safety risk level: none, low, medium, high.",
  },
  { inputType: "trace", userInput: "Label presence of hallucination: yes/no." },
  { inputType: "trace", userInput: "Rate tone professionalism." },
  { inputType: "trace", userInput: "Binary check: format valid or invalid." },
  {
    inputType: "trace",
    userInput: "Confidence level in correctness (0-100%).",
  },
  { inputType: "trace", userInput: "Does this require human review?" },
  { inputType: "trace", userInput: "Is escalation needed?" },
  {
    inputType: "trace",
    userInput: "Would you approve this response for production?",
  },
  {
    inputType: "trace",
    userInput:
      "Evaluate whether this response meets enterprise security standards.",
  },
  {
    inputType: "trace",
    userInput:
      "Identify regulatory or compliance risks (PII, PHI, financial data).",
  },
  {
    inputType: "trace",
    userInput: "Detect potential legal exposure in the response.",
  },
  {
    inputType: "trace",
    userInput: "Check for confidential information leakage.",
  },
  {
    inputType: "trace",
    userInput:
      "Assess whether the answer aligns with internal governance policies.",
  },
  {
    inputType: "trace",
    userInput: "Flag content that could create reputational risk.",
  },
  {
    inputType: "trace",
    userInput:
      "Determine if this response is safe for customer-facing deployment.",
  },
  {
    inputType: "trace",
    userInput: "Verify alignment with brand voice guidelines.",
  },
  {
    inputType: "trace",
    userInput: "Did the agent correctly use the available tools?",
  },
  { inputType: "trace", userInput: "Did the agent fabricate tool outputs?" },
  { inputType: "trace", userInput: "Did it follow the tool-calling protocol?" },
  {
    inputType: "trace",
    userInput: "Were intermediate reasoning steps exposed improperly?",
  },
  {
    inputType: "trace",
    userInput: "Did the system reveal hidden chain-of-thought?",
  },
  { inputType: "trace", userInput: "Is the tool output schema respected?" },
  {
    inputType: "trace",
    userInput: "Did the agent ignore required guardrails?",
  },
  {
    inputType: "thread",
    userInput: "Is the conversation coherent across all turns?",
  },
  {
    inputType: "thread",
    userInput: "Did the assistant contradict itself between messages?",
  },
  {
    inputType: "thread",
    userInput: "Does the assistant maintain context throughout the thread?",
  },
  {
    inputType: "thread",
    userInput: "Is the overall tone consistent across the conversation?",
  },
  {
    inputType: "thread",
    userInput: "Did the assistant properly handle topic changes?",
  },
  { inputType: "trace", userInput: "Umbrella" },
  { inputType: "trace", userInput: "Test" },
  { inputType: "trace", userInput: "No idea" },
  { inputType: "trace", userInput: "Hi" },
  { inputType: "trace", userInput: "You there?" },
];

export const createDefaultSample = (): RuleGeneratorInput[] =>
  DEFAULT_ENTRIES.map((entry) => ({
    id: crypto.randomUUID(),
    inputType: entry.inputType,
    userInput: entry.userInput,
    result: "",
  }));
