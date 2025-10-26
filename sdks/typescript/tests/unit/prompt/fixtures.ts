import { vi } from "vitest";
import { PromptType } from "../../../src/opik/prompt/types";
import type { OpikClient } from "../../../src/opik/client/Client";
import type * as OpikApi from "../../../src/opik/rest_api/api";

/**
 * Shared test fixtures and mock data for Prompt tests
 */

export function createMockOpikClient(): OpikClient {
  return {
    api: {
      prompts: {
        getPromptVersions: vi.fn(),
        restorePromptVersion: vi.fn(),
        retrievePromptVersion: vi.fn(),
        updatePrompt: vi.fn().mockResolvedValue(undefined),
      },
      requestOptions: {},
    },
    deletePrompts: vi.fn().mockResolvedValue(undefined),
  } as unknown as OpikClient;
}

export const basicPromptData = {
  promptId: "test-prompt-id",
  versionId: "test-version-id",
  name: "test-prompt",
  prompt: "Hello {{name}}!",
  type: PromptType.MUSTACHE,
};

export const promptWithMetadata = {
  ...basicPromptData,
  metadata: {
    version: "1.0",
    author: "test-user",
    tags: ["production", "verified"],
  },
};

export const complexMetadata = {
  config: {
    settings: {
      model: "gpt-4",
      temperature: 0.7,
      features: {
        streaming: true,
        cache: false,
      },
    },
    limits: {
      maxTokens: 1000,
      timeout: 30,
    },
  },
  tags: ["ai", "production"],
  counts: [1, 2, 3, 4, 5],
};

export const mustacheTemplates = {
  basic: "Hello {{name}}!",
  multipleVars: "Hello {{name}}, your score is {{score}}!",
  withSpaces: "{{ greeting }}, {{ name }}!",
  nested: "User: {{user.name}}, Age: {{user.age}}",
  sections: "{{#users}}Hello {{name}}! {{/users}}",
  conditionals: "{{#show}}Visible{{/show}}{{^show}}Hidden{{/show}}",
};

export const jinja2Templates = {
  basic: "Hello {{ name }}!",
  multipleVars: "Hello {{ name }}, score: {{ score }}",
  ifStatement: "{% if admin %}Admin{% else %}User{% endif %}",
  forLoop: "{% for item in items %}{{ item }}{% endfor %}",
  filters: "{{ name | upper }}",
};

export function createMockVersionDetail(
  overrides: Partial<OpikApi.PromptVersionDetail> = {}
): OpikApi.PromptVersionDetail {
  return {
    id: "version-id",
    promptId: "prompt-id",
    commit: "abc123de",
    template: "Hello {{name}}!",
    type: "mustache",
    createdAt: new Date(),
    metadata: undefined,
    changeDescription: undefined,
    ...overrides,
  };
}

export function createMockVersionDetailArray(
  count: number,
  baseOverrides: Partial<OpikApi.PromptVersionDetail> = {}
): OpikApi.PromptVersionDetail[] {
  return Array.from({ length: count }, (_, i) =>
    createMockVersionDetail({
      id: `version-${i}`,
      commit: `commit-${String(i).padStart(8, "0")}`,
      template: `Template version ${i}`,
      createdAt: new Date(Date.now() - i * 86400000),
      ...baseOverrides,
    })
  );
}

export const validApiResponse: OpikApi.PromptVersionDetail = {
  promptId: "prompt-id",
  id: "version-id",
  commit: "abc123de",
  template: "Hello {{name}}!",
  type: "mustache",
  createdAt: new Date(),
  metadata: { version: "1.0" },
  changeDescription: "Initial version",
};

export const invalidApiResponses = {
  missingTemplate: {
    promptId: "prompt-id",
    commit: "abc123de",
    id: "version-id",
    createdAt: new Date(),
  } as unknown as OpikApi.PromptVersionDetail,

  missingCommit: {
    promptId: "prompt-id",
    template: "Hello",
    id: "version-id",
    createdAt: new Date(),
  } as unknown as OpikApi.PromptVersionDetail,

  missingPromptId: {
    template: "Hello",
    commit: "abc123de",
    id: "version-id",
    createdAt: new Date(),
  } as unknown as OpikApi.PromptVersionDetail,

  invalidType: {
    promptId: "prompt-id",
    template: "Hello",
    commit: "abc123de",
    type: "invalid-type" as string,
    id: "version-id",
    createdAt: new Date(),
  } as unknown as OpikApi.PromptVersionDetail,
};

export function createMockPromptData(
  overrides: Partial<OpikApi.PromptPublic> = {}
): OpikApi.PromptPublic {
  return {
    id: "prompt-id",
    name: "test-prompt",
    description: undefined,
    tags: [],
    createdAt: new Date(),
    lastUpdatedAt: new Date(),
    ...overrides,
  };
}
