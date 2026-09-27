export type TokenType = 'metric' | 'formula' | 'text' | 'table';

export type OutputFormat = 'percentage' | 'currency' | 'number' | 'text';

export interface SemanticExecutionPlan {
  sourceTable: string;
  sourceTableLabel: string;
  filterConditions: string[];
  inputFields: {
    field: string;
    label: string;
    description: string;
  }[];
  formula: string;
  formulaDescription: string;
  format: OutputFormat;
  explanation: string;
}

export interface SemanticQueryResult {
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
}

export interface PlaceholderToken {
  id: string;
  label: string;
  prompt: string;
  status: 'draft' | 'analyzed' | 'executed' | 'error';
  semanticPlan?: SemanticExecutionPlan;
  resolvedValue?: string;
  unit?: string;
  updatedAt?: string;
}

export interface DocumentContentControl {
  id: string;
  tag: string;
  wordId: string;
  alias: string;
  preview: string;
  occurrences: number;
  tagged: boolean;
}

export type BlockType = 'h1' | 'h2' | 'paragraph' | 'divider' | 'callout';

export interface ReportBlock {
  id: string;
  type: BlockType;
  content: string; // Plain text or text with embedded {{token_id}}
  highlight?: boolean; // Highlight / yellow marker
  bold?: boolean; // Bold text for whole block or rich formatting
}

export interface ReportDocument {
  id: string;
  title: string;
  periodActual: string;
  periodComparable: string;
  materialFocus: string;
  blocks: ReportBlock[];
  tokens: Record<string, PlaceholderToken>;
}

export interface PlaceholderRun {
  id: string;
  configRevision: number;
  promptSnapshot: string;
  planSnapshot: SemanticExecutionPlan;
  executedSql: string;
  result?: SemanticQueryResult;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'APPLIED';
  errorMessage?: string;
  executedAt: string;
  appliedAt?: string;
}

export interface PlaceholderConfig {
  id: string;
  documentId: string;
  tag: string;
  alias?: string;
  prompt?: string;
  duration?: string;
  executionPlan?: SemanticExecutionPlan;
  generatedSql?: string;
  format?: OutputFormat;
  revision: number;
  createdAt: string;
  updatedAt: string;
  lastRun?: PlaceholderRun;
}

export interface GeneratePlaceholderResponse {
  configId: string;
  runId: string;
  revision: number;
  blueprint: SemanticExecutionPlan;
  result?: SemanticQueryResult;
  error?: string;
}

export interface ApplyPlaceholderResponse {
  documentVersion: number;
  updatedControls: number;
  runId: string;
  appliedValue: string;
}

