/** Kinds of report placeholder. */
export type TokenType = 'metric' | 'formula' | 'text' | 'table';

/** How a placeholder's value is formatted. */
export type OutputFormat = 'percentage' | 'currency' | 'number' | 'text';

/** Query blueprint generated for a placeholder prompt; mirrors the backend's `ReportBlueprint`. */
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

/** Executed SQL and its result rows. */
export interface SemanticQueryResult {
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
}

/** In-memory state of a report placeholder. */
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

/** A content control found in the saved Word document. */
export interface DocumentContentControl {
  id: string;
  tag: string;
  wordId: string;
  alias: string;
  preview: string;
  occurrences: number;
  tagged: boolean;
}

/** Kinds of block in the sample report document. */
export type BlockType = 'h1' | 'h2' | 'paragraph' | 'divider' | 'callout';

/** A block of the sample report document. */
export interface ReportBlock {
  id: string;
  type: BlockType;
  content: string; // Plain text or text with embedded {{token_id}}
  highlight?: boolean; // Highlight / yellow marker
  bold?: boolean; // Bold text for whole block or rich formatting
}

/** The sample report document and its placeholder tokens, keyed by token ID. */
export interface ReportDocument {
  id: string;
  title: string;
  periodActual: string;
  periodComparable: string;
  materialFocus: string;
  blocks: ReportBlock[];
  tokens: Record<string, PlaceholderToken>;
}

/** A saved query run with the snapshots it was produced from. */
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

/** A saved placeholder configuration with its latest run. */
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

/** Result of generating and running a blueprint; `error` is set when the query failed. */
export interface GeneratePlaceholderResponse {
  configId: string;
  runId: string;
  revision: number;
  blueprint: SemanticExecutionPlan;
  result?: SemanticQueryResult;
  error?: string;
}

/** Result of writing a run's value into the document. */
export interface ApplyPlaceholderResponse {
  documentVersion: number;
  updatedControls: number;
  runId: string;
  appliedValue: string;
}
