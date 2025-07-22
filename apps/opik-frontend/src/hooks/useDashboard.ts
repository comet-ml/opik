import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import dashboardService from '@/services/dashboardService';
import { Dashboard, CreateDashboardRequest, UpdateDashboardRequest } from '@/types/dashboard';
import { QueryParams } from '@/types/api';

export const DASHBOARD_QUERY_KEYS = {
  all: ['dashboards'] as const,
  lists: () => [...DASHBOARD_QUERY_KEYS.all, 'list'] as const,
  list: (params?: QueryParams) => [...DASHBOARD_QUERY_KEYS.lists(), params] as const,
  details: () => [...DASHBOARD_QUERY_KEYS.all, 'detail'] as const,
  detail: (id: string) => [...DASHBOARD_QUERY_KEYS.details(), id] as const,
};

export function useDashboards(params?: QueryParams) {
  return useQuery({
    queryKey: DASHBOARD_QUERY_KEYS.list(params),
    queryFn: () => dashboardService.getDashboards(params),
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}

export function useDashboard(id: string) {
  return useQuery({
    queryKey: DASHBOARD_QUERY_KEYS.detail(id),
    queryFn: () => dashboardService.getDashboard(id),
    enabled: !!id,
    staleTime: 2 * 60 * 1000, // 2 minutes
  });
}

export function useCreateDashboard() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: CreateDashboardRequest) => dashboardService.createDashboard(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DASHBOARD_QUERY_KEYS.lists() });
    },
  });
}

export function useUpdateDashboard() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateDashboardRequest }) =>
      dashboardService.updateDashboard(id, data),
    onSuccess: (result, { id }) => {
      queryClient.invalidateQueries({ queryKey: DASHBOARD_QUERY_KEYS.detail(id) });
      queryClient.invalidateQueries({ queryKey: DASHBOARD_QUERY_KEYS.lists() });
    },
  });
}

export function useDeleteDashboard() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: string) => dashboardService.deleteDashboard(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DASHBOARD_QUERY_KEYS.lists() });
    },
  });
}

export function useDuplicateDashboard() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, name }: { id: string; name?: string }) =>
      dashboardService.duplicateDashboard(id, name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DASHBOARD_QUERY_KEYS.lists() });
    },
  });
}

export function useDashboardActions(id?: string) {
  const updateMutation = useUpdateDashboard();
  const deleteMutation = useDeleteDashboard();
  const duplicateMutation = useDuplicateDashboard();

  const updateDashboard = useCallback(
    (data: UpdateDashboardRequest) => {
      if (!id) return Promise.reject(new Error('Dashboard ID is required'));
      return updateMutation.mutateAsync({ id, data });
    },
    [id, updateMutation]
  );

  const deleteDashboard = useCallback(() => {
    if (!id) return Promise.reject(new Error('Dashboard ID is required'));
    return deleteMutation.mutateAsync(id);
  }, [id, deleteMutation]);

  const duplicateDashboard = useCallback(
    (name?: string) => {
      if (!id) return Promise.reject(new Error('Dashboard ID is required'));
      return duplicateMutation.mutateAsync({ id, name });
    },
    [id, duplicateMutation]
  );

  return {
    updateDashboard,
    deleteDashboard,
    duplicateDashboard,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
    isDuplicating: duplicateMutation.isPending,
  };
}
