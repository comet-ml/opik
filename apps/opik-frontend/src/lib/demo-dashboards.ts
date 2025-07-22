import { Dashboard } from '@/types/dashboard';

export const demoDashboard: Dashboard = {
  id: 'demo-dashboard',
  name: 'ML Operations Overview',
  description: 'A comprehensive view of your ML model performance and operational metrics',
  layout: {
    grid: [
      {
        id: 'success-rate-kpi',
        x: 0,
        y: 0,
        w: 3,
        h: 2,
        type: 'kpi_card',
        config: {
          title: 'Success Rate',
          dataSource: '/api/metrics/success-rate',
          queryParams: {},
          chartOptions: {},
        },
      },
      {
        id: 'response-time-kpi',
        x: 3,
        y: 0,
        w: 3,
        h: 2,
        type: 'kpi_card',
        config: {
          title: 'Avg Response Time',
          dataSource: '/api/metrics/response-time',
          queryParams: {},
          chartOptions: {},
        },
      },
      {
        id: 'requests-today-kpi',
        x: 6,
        y: 0,
        w: 3,
        h: 2,
        type: 'kpi_card',
        config: {
          title: 'Requests Today',
          dataSource: '/api/metrics/requests-today',
          queryParams: {},
          chartOptions: {},
        },
      },
      {
        id: 'error-rate-kpi',
        x: 9,
        y: 0,
        w: 3,
        h: 2,
        type: 'kpi_card',
        config: {
          title: 'Error Rate',
          dataSource: '/api/metrics/error-rate',
          queryParams: {},
          chartOptions: {},
        },
      },
      {
        id: 'requests-timeline',
        x: 0,
        y: 2,
        w: 8,
        h: 4,
        type: 'line_chart',
        config: {
          title: 'Requests Over Time',
          dataSource: '/api/metrics/requests-timeline',
          queryParams: { period: '24h' },
          chartOptions: {
            colors: ['#8884d8', '#82ca9d'],
            showLegend: true,
            showGrid: true,
          },
        },
      },
      {
        id: 'model-accuracy',
        x: 8,
        y: 2,
        w: 4,
        h: 4,
        type: 'pie_chart',
        config: {
          title: 'Model Accuracy Distribution',
          dataSource: '/api/metrics/model-accuracy',
          queryParams: {},
          chartOptions: {
            colors: ['#00C49F', '#FFBB28', '#FF8042'],
            showLegend: true,
          },
        },
      },
      {
        id: 'error-categories',
        x: 0,
        y: 6,
        w: 6,
        h: 4,
        type: 'bar_chart',
        config: {
          title: 'Error Categories',
          dataSource: '/api/metrics/error-categories',
          queryParams: {},
          chartOptions: {
            colors: ['#ff7300'],
            showGrid: true,
          },
        },
      },
      {
        id: 'usage-heatmap',
        x: 6,
        y: 6,
        w: 6,
        h: 4,
        type: 'heatmap',
        config: {
          title: 'Usage Patterns by Hour',
          dataSource: '/api/metrics/usage-heatmap',
          queryParams: {},
          chartOptions: {
            colors: ['#f0f9ff', '#0ea5e9'],
          },
        },
      },
      {
        id: 'recent-traces',
        x: 0,
        y: 10,
        w: 12,
        h: 6,
        type: 'table',
        config: {
          title: 'Recent Traces',
          dataSource: '/api/traces/recent',
          queryParams: { limit: 20 },
          chartOptions: {},
        },
      },
    ],
  },
  filters: {
    global: {
      timeRange: '24h',
      environment: 'production',
    },
  },
  refreshInterval: 30,
  created: '2024-01-01T00:00:00Z',
  modified: new Date().toISOString(),
};

export const sampleDashboards: Dashboard[] = [
  demoDashboard,
  {
    id: 'performance-dashboard',
    name: 'Performance Monitoring',
    description: 'Monitor system performance and resource usage',
    layout: {
      grid: [
        {
          id: 'cpu-usage',
          x: 0,
          y: 0,
          w: 6,
          h: 4,
          type: 'line_chart',
          config: {
            title: 'CPU Usage',
            dataSource: '/api/metrics/cpu-usage',
            queryParams: {},
            chartOptions: {},
          },
        },
        {
          id: 'memory-usage',
          x: 6,
          y: 0,
          w: 6,
          h: 4,
          type: 'line_chart',
          config: {
            title: 'Memory Usage',
            dataSource: '/api/metrics/memory-usage',
            queryParams: {},
            chartOptions: {},
          },
        },
      ],
    },
    filters: { global: {} },
    refreshInterval: 10,
    created: '2024-01-02T00:00:00Z',
    modified: '2024-01-02T12:00:00Z',
  },
];
