import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
// Using native confirm dialog instead of AlertDialog component
import {
  Code,
  Play,
  Edit,
  Trash2,
  Copy,
  ExternalLink,
  MoreVertical,
  Clock,
  CheckCircle,
  AlertTriangle,
  Loader2,
} from 'lucide-react';
import { CustomApiInstance } from '@/types/customApi';
import customApiService from '@/services/customApiService';

interface ApiListProps {
  instances: CustomApiInstance[];
  loading: boolean;
  onEdit: (api: CustomApiInstance) => void;
  onDelete: (instanceName: string) => void;
  onTest: (instanceName: string) => void;
  onCopyEndpoint: (instanceName: string) => void;
  onOpenEndpoint: (instanceName: string) => void;
}

const ApiList: React.FC<ApiListProps> = ({
  instances,
  loading,
  onEdit,
  onDelete,
  onTest,
  onCopyEndpoint,
  onOpenEndpoint,
}) => {
  const [deleteApi, setDeleteApi] = useState<string | null>(null);

  const handleDelete = () => {
    if (deleteApi) {
      onDelete(deleteApi);
      setDeleteApi(null);
    }
  };

  const getStatusIcon = (status?: string) => {
    switch (status) {
      case 'active':
        return <CheckCircle className="h-4 w-4 text-green-500" />;
      case 'error':
        return <AlertTriangle className="h-4 w-4 text-red-500" />;
      case 'inactive':
        return <Clock className="h-4 w-4 text-gray-500" />;
      default:
        return <CheckCircle className="h-4 w-4 text-green-500" />;
    }
  };

  const formatDate = (dateString?: string) => {
    if (!dateString) return 'Unknown';
    try {
      return new Date(dateString).toLocaleDateString();
    } catch {
      return 'Invalid date';
    }
  };

  const truncateCode = (code: string, maxLength: number = 100) => {
    if (code.length <= maxLength) return code;
    return code.substring(0, maxLength) + '...';
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin" />
        <span className="ml-2">Loading APIs...</span>
      </div>
    );
  }

  if (instances.length === 0) {
    return (
      <Alert>
        <Code className="h-4 w-4" />
        <AlertDescription>
          No custom APIs found. Create your first API to get started.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {instances.map((api) => (
          <Card key={api.instance_name} className="relative group hover:shadow-md transition-shadow">
            <CardHeader className="pb-3">
              <div className="flex items-start justify-between">
                <div className="flex items-center space-x-2">
                  {getStatusIcon(api.status)}
                  <CardTitle className="text-lg font-semibold">
                    {api.instance_name}
                  </CardTitle>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="sm" className="h-8 w-8 p-0">
                      <MoreVertical className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="w-48">
                    <DropdownMenuItem onClick={() => onTest(api.instance_name)}>
                      <Play className="h-4 w-4 mr-2" />
                      Test API
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => onEdit(api)}>
                      <Edit className="h-4 w-4 mr-2" />
                      Edit
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem onClick={() => onCopyEndpoint(api.instance_name)}>
                      <Copy className="h-4 w-4 mr-2" />
                      Copy Endpoint
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => onOpenEndpoint(api.instance_name)}>
                      <ExternalLink className="h-4 w-4 mr-2" />
                      Open in Browser
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem 
                      onClick={() => {
                        if (window.confirm(`Are you sure you want to delete the API "${api.instance_name}"? This action cannot be undone and will immediately stop the API endpoint.`)) {
                          onDelete(api.instance_name);
                        }
                      }}
                      className="text-red-600 focus:text-red-600"
                    >
                      <Trash2 className="h-4 w-4 mr-2" />
                      Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
              {api.description && (
                <CardDescription className="text-sm">
                  {api.description}
                </CardDescription>
              )}
            </CardHeader>
            
            <CardContent className="space-y-3">
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground font-semibold">Endpoint:</div>
                <div className="bg-muted p-2 rounded text-xs font-mono text-muted-foreground break-all">
                  {customApiService.getApiEndpoint(api.instance_name)}
                </div>
              </div>

              <div className="space-y-2">
                <div className="text-xs text-muted-foreground font-semibold">Code Preview:</div>
                <div className="bg-muted p-2 rounded text-xs font-mono max-h-20 overflow-hidden">
                  <pre className="whitespace-pre-wrap text-muted-foreground">
                    {truncateCode(api.code)}
                  </pre>
                </div>
              </div>

              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  Created: {formatDate(api.created_at)}
                </span>
                {api.updated_at && (
                  <span>
                    Updated: {formatDate(api.updated_at)}
                  </span>
                )}
              </div>

              <div className="flex items-center space-x-2 pt-2">
                <Button 
                  size="sm" 
                  variant="outline" 
                  onClick={() => onTest(api.instance_name)}
                  className="flex-1"
                >
                  <Play className="h-3 w-3 mr-1" />
                  Test
                </Button>
                <Button 
                  size="sm" 
                  variant="outline" 
                  onClick={() => onEdit(api)}
                  className="flex-1"
                >
                  <Edit className="h-3 w-3 mr-1" />
                  Edit
                </Button>
                <Button 
                  size="sm" 
                  variant="outline" 
                  onClick={() => onCopyEndpoint(api.instance_name)}
                  className="px-2"
                >
                  <Copy className="h-3 w-3" />
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="text-sm text-muted-foreground text-center pt-4">
        {instances.length} API{instances.length !== 1 ? 's' : ''} total
      </div>
    </div>
  );
};

export default ApiList; 