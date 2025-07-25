import { z } from 'zod';
import { WidgetType } from '@/types/dashboard';

export const widgetConfigSchema = z.object({
  title: z.string().min(1, 'Title is required').max(100, 'Title must be less than 100 characters'),
  dataSource: z.string().url('Must be a valid URL').optional(),
  queryParams: z.record(z.any()).optional(),
  chartOptions: z.record(z.any()).optional(),
  refreshInterval: z.number().min(5).max(3600).optional(),
});

export const dashboardLayoutSchema = z.object({
  id: z.string(),
  x: z.number().min(0),
  y: z.number().min(0),
  w: z.number().min(1).max(12),
  h: z.number().min(1).max(10),
  type: z.enum(['line_chart', 'bar_chart', 'pie_chart', 'table', 'kpi_card', 'heatmap', 'area_chart', 'donut_chart', 'scatter_plot', 'gauge_chart', 'progress_bar', 'number_card', 'funnel_chart', 'horizontal_bar_chart']),
  config: widgetConfigSchema,
});

export const dashboardSchema = z.object({
  id: z.string(),
  name: z.string().min(1, 'Name is required').max(100, 'Name must be less than 100 characters'),
  description: z.string().max(500, 'Description must be less than 500 characters').optional(),
  layout: z.object({
    grid: z.array(dashboardLayoutSchema),
  }),
  filters: z.record(z.any()).optional(), // Updated to match backend structure
  refreshInterval: z.number().min(5).max(3600).optional(), // 5 seconds to 1 hour
  createdAt: z.string().optional(),
  createdBy: z.string().optional(),
  lastUpdatedAt: z.string().optional(),
  lastUpdatedBy: z.string().optional(),
  // Legacy fields for compatibility
  created: z.string().optional(),
  modified: z.string().optional(),
});

export const createDashboardSchema = z.object({
  name: z.string().min(1, 'Name is required').max(100, 'Name must be less than 100 characters'),
  description: z.string().max(500, 'Description must be less than 500 characters').optional(),
});

export function validateWidgetConfig(config: any) {
  try {
    return widgetConfigSchema.parse(config);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new Error(error.errors.map(e => e.message).join(', '));
    }
    throw error;
  }
}

export function validateDashboard(dashboard: any) {
  try {
    return dashboardSchema.parse(dashboard);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new Error(error.errors.map(e => e.message).join(', '));
    }
    throw error;
  }
}

export function validateCreateDashboard(data: any) {
  try {
    return createDashboardSchema.parse(data);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new Error(error.errors.map(e => e.message).join(', '));
    }
    throw error;
  }
}

export function isValidWidgetType(type: string): type is WidgetType {
  return ['line_chart', 'bar_chart', 'pie_chart', 'table', 'kpi_card', 'heatmap'].includes(type);
}

export function validateUrl(url: string): boolean {
  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
}

export function sanitizeHtml(html: string): string {
  // Basic HTML sanitization - in production, use a proper library like DOMPurify
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/<iframe\b[^<]*(?:(?!<\/iframe>)<[^<]*)*<\/iframe>/gi, '')
    .replace(/javascript:/gi, '')
    .replace(/on\w+="[^"]*"/gi, '');
}
