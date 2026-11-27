export type Role = 'OWNER' | 'MEMBER';

export interface Me {
  name: string;
  role: Role;
  workspaceId: string;
  workspaceName: string;
  workspaces: { id: string; name: string; role: string }[];
}

export interface Meta {
  aiMode: 'ai' | 'offline';
  model: string | null;
  embeddings: string;
  codeProvider: string;
  prometheusHost: string | null;
  loki: boolean;
  tempo: boolean;
  grafana: boolean;
  serviceMap: boolean;
  actions: boolean;
  knowledgeDocs: number;
  problems: string[];
}

export type InvestigationStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface InvestigationSummary {
  id: string;
  question: string;
  service: string | null;
  trigger_type: string;
  status: InvestigationStatus;
  title: string | null;
  confidence: string | null;
  created_at: string;
  finished_at: string | null;
}

export interface Blame {
  commit: string;
  shortCommit: string;
  author: string | null;
  authorLogin: string | null;
  date: string | null;
  summary: string | null;
  prNumber: number | null;
  prTitle: string | null;
  prUrl: string | null;
}

export interface CodeContext {
  provider: string;
  file: string;
  line: number;
  ref: string;
  startLine: number;
  lines: string[];
  blame: Blame | null;
  lineCommits: (string | null)[];
  recentChanges: Blame[];
  note: string | null;
}

export interface Step {
  index: number;
  tool: string;
  args: Record<string, unknown>;
  status: 'running' | 'done' | 'failed';
  summary?: string;
  durationMs?: number;
}

export interface Chart {
  title: string;
  query: string;
  points: [number, number][];
  summary: { baselineMean?: number; recentMean?: number; changePercent?: number; max?: number; min?: number };
}

export interface KnowledgeHit {
  docId: string;
  title: string;
  type: string;
  score: number;
  excerpt: string;
}

export interface Report {
  title?: string;
  summary?: string;
  impact?: string;
  likelyCause?: string;
  confidence?: string;
  evidence?: { signal: string; before: string; after: string; source?: string }[];
  codeFinding?: { file: string; line: number; explanation: string } | null;
  suggestedFix?: { explanation: string; diff?: string } | null;
  knowledgeUsed?: string[];
  nextSteps?: string[];
  unknowns?: string[];
}

export interface Investigation {
  id: string;
  question: string;
  service: string | null;
  lookbackMinutes: number;
  trigger: string;
  status: InvestigationStatus;
  mode: 'ai' | 'offline' | null;
  model: string | null;
  createdAt: string;
  finishedAt: string | null;
  durationSeconds: number | null;
  stepCount: number;
  failure: string | null;
  title?: string;
  confidence?: string;
  steps: Step[];
  charts: Chart[];
  code: CodeContext[];
  knowledge: KnowledgeHit[];
  actions: string[];
  report: Report | null;
  chat: { role: 'user' | 'assistant'; text: string; at: string }[];
}

export interface ErrorGroup {
  id: string;
  service: string;
  environment: string | null;
  exceptionClass: string;
  message: string | null;
  count: number;
  firstSeen: string;
  lastSeen: string;
  httpMethod: string | null;
  path: string | null;
  commitSha: string | null;
  topFrame?: string;
}

export interface KnowledgeDoc {
  id: string;
  title: string;
  type: 'runbook' | 'incident' | 'doc';
  source: string;
  createdAt: string;
}

export interface ActionItem {
  id: string;
  investigation_id: string | null;
  type: string;
  title: string;
  payload: string;
  reason: string | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'FAILED';
  proposed_at: string;
  decided_by: string | null;
  decided_at: string | null;
  result: string | null;
}

export interface Schedule {
  id: string;
  name: string;
  question: string;
  service: string | null;
  interval_minutes: number;
  enabled: boolean;
  last_run_at: string | null;
  last_investigation_id: string | null;
  next_run_at: string;
}
