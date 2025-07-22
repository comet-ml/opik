import { useState, useCallback, useEffect } from 'react';
import dataService, { ApiTestResult, FieldInfo } from '@/services/dataService';
import { DataSource } from '@/types/widget';

export type ConnectionStatus = 'idle' | 'loading' | 'success' | 'error';

export interface UseDataPreviewResult {
  // Connection state
  connectionStatus: ConnectionStatus;
  connectionMessage: string;
  
  // Data state
  previewData: any;
  availableFields: FieldInfo[];
  
  // Actions
  testConnection: () => Promise<void>;
  clearPreview: () => void;
  
  // Field suggestions based on widget type
  getSuggestedFields: (widgetType: string) => { xAxis: FieldInfo[]; yAxis: FieldInfo[] };
}

export function useDataPreview(
  dataSource: DataSource,
  globalFilters: Record<string, any> = {}
): UseDataPreviewResult {
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('idle');
  const [connectionMessage, setConnectionMessage] = useState('');
  const [previewData, setPreviewData] = useState<any>(null);
  const [availableFields, setAvailableFields] = useState<FieldInfo[]>([]);

  const testConnection = useCallback(async () => {
    if (!dataSource.endpoint) {
      setConnectionStatus('error');
      setConnectionMessage('API endpoint is required');
      return;
    }

    setConnectionStatus('loading');
    setConnectionMessage('Testing connection...');
    setPreviewData(null);
    setAvailableFields([]);

    try {
      console.log('Testing connection with dataSource:', dataSource);
      const result: ApiTestResult = await dataService.testApiConnection(dataSource, globalFilters);
      console.log('API test result:', result);
      
      if (result.success && result.data) {
        setConnectionStatus('success');
        setConnectionMessage(result.message);
        setPreviewData(result.data);
        
        // Extract fields from the response data
        console.log('Extracting fields from data:', result.data);
        const fields = dataService.extractFieldsFromData(result.data);
        console.log('Extracted fields:', fields);
        setAvailableFields(fields);
      } else {
        setConnectionStatus('error');
        setConnectionMessage(result.message || 'Connection failed');
        setPreviewData(null);
        setAvailableFields([]);
      }
    } catch (error: any) {
      console.error('Connection test failed:', error);
      setConnectionStatus('error');
      setConnectionMessage(error.message || 'Unexpected error occurred');
      setPreviewData(null);
      setAvailableFields([]);
    }
  }, [dataSource, globalFilters]);

  const clearPreview = useCallback(() => {
    setConnectionStatus('idle');
    setConnectionMessage('');
    setPreviewData(null);
    setAvailableFields([]);
  }, []);

  const getSuggestedFields = useCallback((widgetType: string) => {
    return dataService.getSuggestedFields(availableFields, widgetType);
  }, [availableFields]);

  // Auto-test connection when dataSource changes and has an endpoint
  useEffect(() => {
    if (dataSource.endpoint && connectionStatus === 'idle') {
      // Don't auto-test immediately, let user trigger it
      // This prevents unnecessary API calls during configuration
    }
  }, [dataSource.endpoint, connectionStatus]);

  return {
    connectionStatus,
    connectionMessage,
    previewData,
    availableFields,
    testConnection,
    clearPreview,
    getSuggestedFields,
  };
} 