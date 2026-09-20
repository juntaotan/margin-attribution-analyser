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

