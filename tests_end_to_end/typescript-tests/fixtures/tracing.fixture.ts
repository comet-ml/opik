import { test as base, BaseFixtures } from './base.fixture';
import { TracesPage } from '../page-objects/traces.page';
import { ThreadsPage } from '../page-objects/threads.page';
import { TraceConfig, SpanConfig, ThreadConfig } from '../helpers/test-helper-client';

export type TracingFixtures = {
  tracesPage: TracesPage;
  threadsPage: ThreadsPage;
  createTracesDecorator: void;
  createTracesClient: void;
  createTracesWithSpansClient: { traceConfig: TraceConfig; spanConfig: SpanConfig };
  createTracesWithSpansDecorator: { traceConfig: TraceConfig; spanConfig: SpanConfig };
  createTraceWithAttachmentClient: string;
  createTraceWithAttachmentDecorator: string;
  createTraceWithSpanAttachment: { attachmentName: string; spanName: string };
  createThreadsDecorator: ThreadConfig[];
  createThreadsClient: ThreadConfig[];
};

// Trace configuration matching Python fixtures
const defaultTraceConfig: TraceConfig = {
  count: 5,
  prefix: 'client-trace-',
  tags: ['c-tag1', 'c-tag2'],
  metadata: { 'c-md1': 'val1', 'c-md2': 'val2' },
  feedback_scores: [
    { name: 'c-score1', value: 0.1 },
    { name: 'c-score2', value: 7 },
  ],
};

const defaultSpanConfig: SpanConfig = {
  count: 2,
  prefix: 'client-span-',
  tags: ['d-span1', 'd-span2'],
  metadata: { 'd-md1': 'val1', 'd-md2': 'val2' },
  feedback_scores: [
    { name: 's-score1', value: 0.93 },
    { name: 's-score2', value: 5 },
  ],
};

const decoratorTraceConfig: TraceConfig = {
  count: 5,
  prefix: 'decorator-trace-',
  tags: ['d-tag1', 'd-tag2'],
  metadata: { 'd-md1': 'val1', 'd-md2': 'val2' },
  feedback_scores: [
    { name: 'd-score1', value: 0.1 },
    { name: 'd-score2', value: 7 },
  ],
};

const decoratorSpanConfig: SpanConfig = {
  count: 2,
  prefix: 'decorator-span-',
  tags: ['d-span1', 'd-span2'],
  metadata: { 'd-md1': 'val1', 'd-md2': 'val2' },
  feedback_scores: [
    { name: 's-score1', value: 0.93 },
    { name: 's-score2', value: 5 },
  ],
};

// Default thread configurations
const defaultThreadConfigs: ThreadConfig[] = [
  {
    thread_id: 'thread_1',
    inputs: ['input1_1', 'input1_2', 'input1_3'],
    outputs: ['output1_1', 'output1_2', 'output1_3'],
  },
  {
    thread_id: 'thread_2',
    inputs: ['input2_1', 'input2_2', 'input2_3'],
    outputs: ['output2_1', 'output2_2', 'output2_3'],
  },
  {
    thread_id: 'thread_3',
    inputs: ['input3_1', 'input3_2', 'input3_3'],
    outputs: ['output3_1', 'output3_2', 'output3_3'],
  },
];

export const test = base.extend<BaseFixtures & TracingFixtures>({
  tracesPage: async ({ page }, use) => {
    const tracesPage = new TracesPage(page);
    await tracesPage.initialize();
    await use(tracesPage);
  },

  threadsPage: async ({ page }, use) => {
    const threadsPage = new ThreadsPage(page);
    await use(threadsPage);
  },

  createTracesDecorator: async ({ helperClient, projectName }, use) => {
    // Ensure project exists before creating traces
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    await helperClient.createTracesDecorator(projectName, 25, 'test-trace-');
    await helperClient.waitForTracesVisible(projectName, 25, 30);
    await use();

    // Cleanup
    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTracesClient: async ({ helperClient, projectName }, use) => {
    // Ensure project exists before creating traces
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    await helperClient.createTracesClient(projectName, 25, 'test-trace-');
    await helperClient.waitForTracesVisible(projectName, 25, 30);
    await use();

    // Cleanup
    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTracesWithSpansClient: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    await helperClient.createTracesWithSpansClient(
      projectName,
      defaultTraceConfig,
      defaultSpanConfig
    );
    await helperClient.waitForTracesVisible(projectName, defaultTraceConfig.count, 30);
    await use({ traceConfig: defaultTraceConfig, spanConfig: defaultSpanConfig });

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTracesWithSpansDecorator: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    await helperClient.createTracesWithSpansDecorator(
      projectName,
      decoratorTraceConfig,
      decoratorSpanConfig
    );
    await helperClient.waitForTracesVisible(projectName, decoratorTraceConfig.count, 30);
    await use({ traceConfig: decoratorTraceConfig, spanConfig: decoratorSpanConfig });

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTraceWithAttachmentClient: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    // Using a relative path from the test-helper-service location
    const attachmentPath = '../../test_files/attachments/test-image1.jpg';
    const attachmentName = await helperClient.createTraceWithAttachmentClient(
      projectName,
      attachmentPath
    );
    await use(attachmentName);

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTraceWithAttachmentDecorator: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    const attachmentPath = '../../test_files/attachments/test-image1.jpg';
    const attachmentName = await helperClient.createTraceWithAttachmentDecorator(
      projectName,
      attachmentPath
    );
    await use(attachmentName);

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createTraceWithSpanAttachment: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    const attachmentPath = '../../test_files/attachments/test-image1.jpg';
    const { attachmentName, spanName } = await helperClient.createTraceWithSpanAttachment(
      projectName,
      attachmentPath
    );
    await use({ attachmentName, spanName });

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createThreadsDecorator: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    const threadConfigs = await helperClient.createThreadsDecorator(
      projectName,
      defaultThreadConfigs
    );
    await use(threadConfigs);

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createThreadsClient: async ({ helperClient, projectName }, use) => {
    await helperClient.createProject(projectName);
    await helperClient.waitForProjectVisible(projectName, 10);

    const threadConfigs = await helperClient.createThreadsClient(
      projectName,
      defaultThreadConfigs
    );
    await use(threadConfigs);

    try {
      await helperClient.deleteProject(projectName);
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },
});

export { expect } from '@playwright/test';
