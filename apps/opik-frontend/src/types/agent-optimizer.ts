// Message types matching backend
export type AgentOptimizerMessageType = 
  | 'header'
  | 'trace_info'
  | 'prompt_list'
  | 'warning'
  | 'user_input_request'
  | 'options_menu'
  | 'diff_view'
  | 'progress'
  | 'assertion_results'
  | 'test_result'
  | 'success_message'
  | 'error'
  | 'system_message';

// User input request subtypes
export type UserInputRequestSubtype =
  | 'agent_endpoint'
  | 'assertions'
  | 'choice'
  | 'text_input'
  | 'confirmation';

// Change item for diffs
export interface ChangeItem {
  id: string;
  promptName: string;
  changeType: 'system' | 'user' | 'assistant' | 'text';
  messageIndex: number | null;
  originalContent: string;
  modifiedContent: string;
}

// Assertion result
export interface AssertionResult {
  assertion: string;
  passed: boolean;
  reason: string;
}

// Option for menus
export interface MenuOption {
  id: string;
  label: string;
  description?: string;
}

// Message structure
export interface AgentOptimizerMessage {
  id: string;
  type: AgentOptimizerMessageType;
  content: string;
  timestamp: number;
  
  // For user_input_request
  inputSubtype?: UserInputRequestSubtype;
  
  // For options_menu
  options?: MenuOption[];
  allowMultiple?: boolean;
  
  // For diff_view
  changes?: ChangeItem[];
  
  // For assertion_results
  assertionResults?: AssertionResult[];
  
  // For progress
  step?: string;
  iteration?: number;
  maxIterations?: number;
  status?: string;
  
  // For trace_info
  traceData?: {
    input_data: any;
    final_output: string;
    prompts: Array<{
      name: string;
      type: string;
      preview: string;
      messages?: Array<{ role: string; content: string }>;
    }>;
  };
  
  // For test_result
  testPassed?: boolean;
  
  // State flags
  isLoading?: boolean;
  isError?: boolean;
  waitingForResponse?: boolean;
}

// Session state
export interface SessionState {
  phase: string;
  traceId: string;
  agentEndpoint?: string;
  assertions?: string[];
  hasModifiedPrompts?: boolean;
  optimizationComplete?: boolean;
}

// Response types
export interface UserResponse {
  responseType: UserInputRequestSubtype | 'menu_selection' | 'menu_choice';
  data: any; // Type depends on responseType
}

// History response
export interface OptimizerHistoryResponse {
  content: AgentOptimizerMessage[];
  phase: string;
  state: SessionState;
}

// Streaming args
export interface OptimizerRunStreamingArgs {
  message?: string;
  agentEndpoint?: string;
  response?: UserResponse;
  signal: AbortSignal;
  onAddChunk: (message: Partial<AgentOptimizerMessage>) => void;
}

// Streaming return
export interface OptimizerRunStreamingReturn {
  error: string | null;
}

// Chat type for UI state
export interface OptimizerChatType {
  value: string;
  messages: AgentOptimizerMessage[];
}
