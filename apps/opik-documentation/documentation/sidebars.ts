import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';
import apiSidebar from './docs/reference/rest_api/sidebar';

/**
 * Creating a sidebar enables you to:
 - create an ordered group of docs
 - render a sidebar for each doc of that group
 - provide next/previous navigation

 The sidebars can be generated from the filesystem, or explicitly defined here.

 Create as many sidebars as you want.
 */
const sidebars: SidebarsConfig = {
  guideSidebar: [
    'home',
    'quickstart',
    {
      type: 'category',
      label: 'Self-host',
      collapsed: true,
      items: ['self-host/overview', 'self-host/local_deployment', 'self-host/kubernetes']
    },
    {
      type: 'category',
      label: 'Tracing',
      collapsed: false,
      items: ['tracing/log_traces', 'tracing/log_multimodal_traces', 'tracing/log_distributed_traces', 'tracing/annotate_traces', {
        type: 'category',
        label: 'Integrations',
        collapsed: true,
        items: ['tracing/integrations/overview', 'tracing/integrations/openai', 'tracing/integrations/litellm', 'tracing/integrations/langchain',
                'tracing/integrations/langgraph', 'tracing/integrations/llama_index', 'tracing/integrations/ollama', 'tracing/integrations/predibase',
                'tracing/integrations/ragas']
      }],
    },
    {
      type: 'category',
      label: 'Evaluation',
      collapsed: false,
      items: ['evaluation/concepts', 'evaluation/manage_datasets', 'evaluation/evaluate_your_llm', {
        type: 'category',
        label: 'Metrics',
        collapsed: true,
        items: ['evaluation/metrics/overview', 'evaluation/metrics/heuristic_metrics', 'evaluation/metrics/hallucination',
                'evaluation/metrics/moderation', 'evaluation/metrics/answer_relevance', 'evaluation/metrics/context_precision',
                'evaluation/metrics/context_recall', 'evaluation/metrics/custom_metric']
      }],
    },
    {
      type: 'category',
      label: 'Testing',
      collapsed: false,
      items: ['testing/pytest_integration']
    },
    {
      type: 'category',
      label: 'Cookbooks',
      collapsed: false,
      items: ['cookbook/openai', 'cookbook/litellm', 'cookbook/langchain', 'cookbook/langgraph', 'cookbook/llama-index', 'cookbook/ollama', 'cookbook/predibase',
              'cookbook/ragas', 'cookbook/evaluate_hallucination_metric', 'cookbook/evaluate_moderation_metric']
    },
    'faq'
  ],
  rest_api: apiSidebar
};

export default sidebars;
