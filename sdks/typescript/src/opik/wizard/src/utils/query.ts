import axios from 'axios';
import type { ZodSchema } from 'zod';
import { zodToJsonSchema } from 'zod-to-json-schema';
import type { AIModel } from './types';
import { getCloudUrl } from './urls';
import { AxiosError } from 'axios';
import { debug } from './debug';
import { RateLimitError } from './errors';

export interface QueryOptions<S> {
  message: string;
  model?: AIModel;
  schema: ZodSchema<S>;
  apiKey: string;
  workspaceName: string;
}

// Map wizard model names to backend-compatible model names
const MODEL_MAP: Record<AIModel, string> = {
  'o4-mini': 'gpt-4o-mini',
  'gemini-2.5-flash': 'gemini-2.0-flash-exp',
  'gemini-2.5-pro': 'gemini-2.0-pro-exp',
};

export const query = async <S>({
  message,
  model = 'o4-mini',
  schema,
  apiKey,
  workspaceName,
}: QueryOptions<S>): Promise<S> => {
  const fullSchema = zodToJsonSchema(schema, 'schema');
  const jsonSchema = fullSchema.definitions?.schema || fullSchema;

  const backendModel = MODEL_MAP[model] || model;

  debug('Full schema:', JSON.stringify(fullSchema, null, 2));
  debug('Query request:', {
    url: `${getCloudUrl()}opik/api/v1/private/chat/completions`,
    model: backendModel,
    message: message.substring(0, 100) + '...',
    response_format: jsonSchema,
  });

  // Prepare request body following OpenAI chat completions format
  const requestBody = {
    model: backendModel,
    messages: [
      {
        role: 'user',
        content: message,
      },
    ],
    response_format: {
      type: 'json_schema',
      json_schema: {
        name: 'schema',
        schema: jsonSchema,
        strict: true,
      },
    },
    stream: false,
  };

  const response = await axios
    .post<{
      choices: Array<{
        message: {
          content: string;
          role: string;
        };
        finish_reason: string;
      }>;
      model: string;
      usage?: {
        prompt_tokens: number;
        completion_tokens: number;
        total_tokens: number;
      };
    }>(`${getCloudUrl()}opik/api/v1/private/chat/completions`, requestBody, {
      headers: {
        'Content-Type': 'application/json',
        'Comet-Workspace': workspaceName,
        Authorization: `${apiKey}`,
      },
    })
    .catch((error) => {
      debug('Query error:', error);

      if (error instanceof AxiosError) {
        if (error.response?.status === 429) {
          throw new RateLimitError();
        }
        if (error.response?.status === 401) {
          throw new Error('Invalid API key. Please check your Opik API key.');
        }
        if (error.response?.status === 400) {
          throw new Error(
            `Bad request: ${
              error.response.data?.message || 'Invalid request format'
            }`,
          );
        }
      }

      throw error;
    });

  debug('Query response:', {
    status: response.status,
    model: response.data.model,
    usage: response.data.usage,
    finish_reason: response.data.choices[0]?.finish_reason,
  });

  // Extract the JSON content from the LLM response
  const content = response.data.choices[0]?.message?.content;
  if (!content) {
    throw new Error('No response content from LLM');
  }

  let parsedContent: unknown;
  try {
    parsedContent = JSON.parse(content);
  } catch {
    debug('Failed to parse LLM response as JSON:', content);
    throw new Error('Invalid JSON response from LLM');
  }

  // Validate the parsed content against the schema
  const validation = schema.safeParse(parsedContent);

  if (!validation.success) {
    debug('Validation error:', validation.error);
    debug('Response content:', parsedContent);
    throw new Error(`Invalid response from LLM: ${validation.error.message}`);
  }

  return validation.data;
};
