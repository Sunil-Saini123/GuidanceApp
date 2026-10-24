export type EventType =
  | 'INTENT_PARSED'
  | 'SCREEN_CHANGED'
  | 'MEDIATOR_DIFF'
  | 'ELEMENT_SEARCH'
  | 'ELEMENT_FOUND'
  | 'STEP_ADVANCED'
  | 'WRONG_PATH'
  | 'REROUTE_TRIGGERED'
  | 'AI_PROMPT_SENT'
  | 'AI_RESPONSE_RECEIVED'
  | 'DESTINATION_REACHED'
  | 'SYSTEM';

export interface BoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface TelemetryEvent {
  id: string;
  type: EventType;
  timestamp: number;
  tag: string;
  title: string;
  description: string;
  packageName?: string;
  screenTitle?: string;
  stepIndex?: number;
  totalSteps?: number;
  currentStep?: string;
  targetBounds?: BoundingBox;
  elements?: Array<{
    name: string;
    is_clickable?: boolean;
    class_name?: string;
    resource_id?: string;
    bounds?: BoundingBox;
  }>;
  mediatorData?: {
    totalVisible: number;
    previouslyKnown: number;
    newAdded: string[];
    action: 'SKIPPED_NO_CHANGE' | 'DELTA_ADDED' | 'FULL_REFRESH';
  };
  aiData?: {
    tier: string;
    promptPreview?: string;
    rawResponse?: string;
    durationMs?: number;
    model?: string;
  };
  payload?: any;
}

export interface AppState {
  browserConnected: boolean;
  phoneConnected: boolean;
  activePackage: string;
  activeScreenTitle: string;
  currentTask: string;
  currentStep: string;
  totalSteps: number;
  stepIndex: number;
  hudState: 'ON_TRACK' | 'OFF_TRACK' | 'FOUND' | 'SEARCHING' | 'SUCCESS' | 'IDLE';
  lastTargetBounds?: BoundingBox;
  lastElementsCount: number;
  totalEventsReceived: number;
}

export interface NavGraphTransition {
  to_id: string;
  to_title: string;
  action: string;
  type: string;
  count?: number;
}

export interface NavGraphScreen {
  id: string;
  title: string;
  root_class?: string;
  visit_count?: number;
  last_seen?: number;
  elements: string[];
  element_count?: number;
  transitions: NavGraphTransition[];
}

export interface NavGraphApp {
  package_name: string;
  display_name: string;
  screen_count: number;
  screens: NavGraphScreen[];
}

export interface NavGraphData {
  generated_at?: number;
  total_apps: number;
  apps: NavGraphApp[];
  active_stacks?: Record<string, string[]>;
}
