import { PromptTemplate } from '@langchain/core/prompts';
import { OPIK_ENV_VARS } from './env-constants';

export const baseFilterFilesPromptTemplate = new PromptTemplate({
  inputVariables: [
    'documentation',
    'file_list',
    'integration_name',
    'integration_rules',
  ],
  template: `You are an Opik installation CLI, a master AI programming assistant that implements Opik SDK for {integration_name} projects.
Given the following list of file paths from a project, determine which files are likely to require modifications 
to integrate Opik for LLM observability and tracing. Use the installation documentation as a reference for what files might need modifications.

IMPORTANT: Opik uses a client-based pattern with the \`Opik\` class, NOT a provider pattern. Look for:
- Main entry points or initialization files
- Files with LLM/AI calls (OpenAI, Anthropic, LangChain, etc.)
- API route handlers or service files
- Configuration or setup files

You can create new files if needed, but prefer modifying existing files when appropriate.

You should return all files that need to be looked at or modified to integrate Opik. Return them in the order you would like to see them processed, with new files first, followed by the files that you want to update.

Rules:
- Focus on files that contain LLM calls or would benefit from observability
- Avoid test files (*.test.*, *.spec.*), config files (tsconfig.json, etc.), and type definition files (*.d.ts)
- If you are unsure, return the file - it's better to have more files than less
- If two files might need the same content, return both
- New files should not conflict with existing files
- For TypeScript projects, prefer .ts and .tsx files
- Follow the project's existing file structure and naming conventions
- Look for initialization or setup files where Opik client can be created
{integration_rules}
- Do NOT look for provider files or React context - Opik doesn't use that pattern

Installation documentation:
{documentation}

All current files in the repository:

{file_list}`,
});

export const baseGenerateFileChangesPromptTemplate = new PromptTemplate({
  inputVariables: [
    'file_content',
    'documentation',
    'file_path',
    'changed_files',
    'unchanged_files',
    'integration_name',
    'integration_rules',
  ],
  template: `You are an Opik installation CLI, a master AI programming assistant that implements Opik SDK for {integration_name} projects.

Your task is to update the file to integrate Opik for LLM observability and tracing according to the documentation.
Do not return a diff — you should return the complete updated file content.

CRITICAL OPIK PATTERNS:
- Use \`new Opik({{ apiKey, environment }})\` for client initialization
- Import from 'opik' package: \`import {{ Opik, track }} from 'opik'\`
- Use \`@track()\` decorator for automatic function tracing
- Use \`client.trace()\` for manual trace creation
- For short-lived scripts/CLI tools: Always add \`await client.flush()\` before exit
- For integrations: Use \`trackOpenAI()\`, \`OpikTracer\`, etc.
- DO NOT use provider patterns like \`<OpikProvider>\` - that's not how Opik works
- Environment variables: ${OPIK_ENV_VARS.API_KEY} and ${OPIK_ENV_VARS.URL_OVERRIDE}

Rules:
- Preserve the existing code formatting and style
- Only make the changes required by the documentation
- If no changes are needed, return the file as-is
- If the current file is empty and should be created, add the appropriate content
- Follow the project's existing structure and import patterns
- Use relative imports if unsure about the project's import paths
- It's okay not to edit a file if it's not needed
- Ensure TypeScript types are correct if this is a TypeScript project
{integration_rules}


CONTEXT
---

Documentation for integrating Opik with {integration_name}:
{documentation}

The file you are updating is:
{file_path}

Here are the changes you have already made to the project:
{changed_files}

Here are the files that have not been changed yet:
{unchanged_files}

Below is the current file contents:
{file_content}`,
});
