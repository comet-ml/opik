import { expect } from '@playwright/test';
import { test as baseTest } from './openai-responses-stub.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed } from '../core/backend';

const QUERYABLE_TIMEOUT_MS = 120_000;

export const AGENT_GRAPH_NODES = ['Orchestrator', 'Researcher', 'Planner', 'Booking'] as const;

export type AgentGraphNode = (typeof AGENT_GRAPH_NODES)[number];

export interface AgentGraphTraceRef {
  traceId: string;
  traceName: string;
  spanIdOf: Record<AgentGraphNode, string>;
}

export interface AgentGraphTraceFixtures {
  agentGraphTrace: AgentGraphTraceRef;
}

export const test = baseTest.extend<AgentGraphTraceFixtures>({
  agentGraphTrace: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    let traceId: string | null = null;

    try {
      const minted = uuid7();
      const traceName = `${testNamespace}-agent-graph`;
      const spanIdOf = Object.fromEntries(
        AGENT_GRAPH_NODES.map((node) => [node, uuid7()]),
      ) as Record<AgentGraphNode, string>;
      const parentOf: Record<AgentGraphNode, AgentGraphNode | null> = {
        Orchestrator: null,
        Researcher: 'Orchestrator',
        Planner: 'Orchestrator',
        Booking: 'Planner',
      };

      const base = new Date();
      const spans: SpanBatchSeed[] = AGENT_GRAPH_NODES.map((node, index) => {
        const parent = parentOf[node];
        return {
          id: spanIdOf[node],
          traceId: minted,
          name: node,
          type: 'general',
          ...(parent === null ? {} : { parentSpanId: spanIdOf[parent] }),
          startTime: new Date(base.getTime() + index * 10),
          endTime: new Date(base.getTime() + index * 10 + 5),
        };
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: minted,
            name: traceName,
            input: { prompt: 'plan a trip' },
            output: { answer: 'trip planned' },
            metadata: {
              _opik_graph_definition: {
                format: 'mermaid',
                data: 'graph TD;\n  Orchestrator-->Researcher;\n  Orchestrator-->Planner;\n  Planner-->Booking;',
              },
            },
            startTime: base,
            endTime: new Date(base.getTime() + 100),
          },
        ],
      });
      traceId = minted;
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      await expect
        .poll(
          async () =>
            (await backendClient.listSpansPage({ projectId: project.id, traceId: minted })).total,
          {
            message: 'every seeded span must be queryable before the panel is opened',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(AGENT_GRAPH_NODES.length);

      await use({ traceId: minted, traceName, spanIdOf });
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceId !== null) {
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          console.warn('[agentGraphTrace fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './openai-responses-stub.fixture';
