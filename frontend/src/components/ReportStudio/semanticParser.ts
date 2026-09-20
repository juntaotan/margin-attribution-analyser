import { SemanticExecutionPlan } from './types';

/**
 * Semantic Intent Sensing Engine
 * Analyzes natural language prompts to detect:
 * 1. Target Data Table (Source Table)
 * 2. Filter Criteria
 * 3. Input Source Field Mappings
 * 4. Computation Formula & Business Logic Explanation
 */
export function parseSemanticPrompt(
  prompt: string,
  context?: {
    materialId?: string;
    actualStartDate?: string;
    actualEndDate?: string;
    comparableStartDate?: string;
    comparableEndDate?: string;
  }
): SemanticExecutionPlan {
  const normalized = prompt.toLowerCase();
  const mat = context?.materialId || 'MTR-1029 (Stator Punching Lamination)';
  const actualPeriod = `${context?.actualStartDate || '2026-08-01'} to ${context?.actualEndDate || '2026-08-31'}`;

  // 1. Purchase Price Variance / PPV / Procurement Cost
  if (
    normalized.includes('purchase') ||
    normalized.includes('price') ||
    normalized.includes('ppv') ||
    normalized.includes('procurement') ||
    normalized.includes('supplier') ||
    normalized.includes('cost') ||
    normalized.includes('采购') ||
    normalized.includes('单价')
  ) {
    return {
      sourceTable: 'purchase_order_line',
      sourceTableLabel: 'purchase_order_line (Purchase Order Line Items)',
      filterConditions: [
        `inventory_id = '${mat}'`,
        `po_status IN ('RECEIVED', 'INVOICED')`,
        `posting_date BETWEEN '${actualPeriod}'`,
      ],
      inputFields: [
        {
          field: 'purchase_price',
          label: 'Actual Invoiced Unit Price',
          description: 'Net cleared invoice price per unit recorded on verified PO receipts',
        },
        {
          field: 'baseline_standard_cost',
          label: 'Baseline Standard Cost',
          description: 'Initial standard unit cost frozen at beginning of fiscal year',
        },
        {
          field: 'received_quantity',
          label: 'Received Quantity',
          description: 'Accepted inventory receipt volume for weighted average unit price',
        },
      ],
      formula: '((SUM(purchase_price * received_quantity) / SUM(received_quantity)) - baseline_standard_cost) / baseline_standard_cost * 100%',
      formulaDescription: 'Purchase Price Variance Rate (PPV%) = (Weighted Avg PO Price - Baseline Cost) ÷ Baseline Cost × 100%',
      format: 'percentage',
      explanation:
        'Retrieves all matched receipt lines from purchase_order_line for the active audit window, derives weighted effective unit price, and computes variance against frozen baseline standard cost.',
    };
  }

  // 2. Material Overuse / Consumption / Excessive Usage
  if (
    normalized.includes('overuse') ||
    normalized.includes('consumption') ||
    normalized.includes('usage') ||
    normalized.includes('excess') ||
    normalized.includes('issue') ||
    normalized.includes('超耗') ||
    normalized.includes('用料')
  ) {
    return {
      sourceTable: 'inventory_usage',
      sourceTableLabel: 'inventory_usage (Shop-Floor Material Ledger)',
      filterConditions: [
        `component_inventory_id = '${mat}'`,
        `transaction_type IN ('ISSUE', 'RETURN_CREDIT')`,
        `usage_timestamp BETWEEN '${actualPeriod}'`,
      ],
      inputFields: [
        {
          field: 'issued_quantity',
          label: 'Actual Issued Quantity',
          description: 'Net outbound material dispatched from warehouse to shop-floor lines',
        },
        {
          field: 'planned_bom_quantity',
          label: 'Standard BOM Target Quantity',
          description: 'Target planned usage calculated as Work Order Good Units × Standard Unit BOM',
        },
      ],
      formula: '((SUM(issued_quantity) - SUM(planned_bom_quantity)) / SUM(planned_bom_quantity)) * 100%',
      formulaDescription: 'Material Overuse Rate (%) = (Actual Issued Qty - Target BOM Qty) ÷ Target BOM Qty × 100%',
      format: 'percentage',
      explanation:
        'Aggregates shop-floor material dispatches from inventory_usage and reconciles against production order standard bill-of-materials allowances.',
    };
  }

  // 3. Scrap Losses / Shop-Floor Quality Drift / Defect Rate
  if (
    normalized.includes('scrap') ||
    normalized.includes('defect') ||
    normalized.includes('waste') ||
    normalized.includes('rework') ||
    normalized.includes('yield') ||
    normalized.includes('报废') ||
    normalized.includes('损耗')
  ) {
    return {
      sourceTable: 'production',
      sourceTableLabel: 'production (Work Order Execution History)',
      filterConditions: [
        `primary_assembly_id = '${mat}' OR component_id = '${mat}'`,
        `order_status = 'CLOSED'`,
        `completion_date BETWEEN '${actualPeriod}'`,
      ],
      inputFields: [
        {
          field: 'scrap_quantity',
          label: 'Scrapped Units',
          description: 'Disposed defect units identified during stamping and stacking QA checks',
        },
        {
          field: 'input_quantity',
          label: 'Total Input Volume',
          description: 'Initial raw units loaded into manufacturing workstations',
        },
        {
          field: 'scrap_unit_cost',
          label: 'Unit Scrap Cost',
          description: 'Embedded direct material and machining cost sunk per scrap unit',
        },
      ],
      formula: 'SUM(scrap_quantity * scrap_unit_cost)',
      formulaDescription: 'Net Scrap Financial Impact = Total Scrapped Units × Unit Standard Cost',
      format: 'currency',
      explanation:
        'Queries manufacturing completion records from production, filters defect write-offs, and values the total financial loss.',
    };
  }

  // 4. Engineering Change Order / BOM Structural Drift / ECN
  if (
    normalized.includes('bom') ||
    normalized.includes('ecn') ||
    normalized.includes('structural') ||
    normalized.includes('substitute') ||
    normalized.includes('engineering') ||
    normalized.includes('替代')
  ) {
    return {
      sourceTable: 'bill_of_material',
      sourceTableLabel: 'bill_of_material (Engineering BOM Master)',
      filterConditions: [
        `parent_assembly_id = 'ASSY-MAIN-500'`,
        `component_id = '${mat}'`,
        `is_active = TRUE`,
      ],
      inputFields: [
        {
          field: 'bom_unit_consumption',
          label: 'Unit BOM Quota',
          description: 'Required quantity per finished assembly under active revision ECN-2026-08',
        },
        {
          field: 'unit_standard_price',
          label: 'Standard Unit Price',
          description: 'Engineering baseline component valuation',
        },
      ],
      formula: '(new_bom_consumption - old_bom_consumption) * unit_standard_price',
      formulaDescription: 'Structural BOM Impact = (New Unit Quota - Prior Unit Quota) × Unit Price',
      format: 'currency',
      explanation:
        'Compares current engineering bill of materials against baseline revision to quantify cost variance stemming from ECN design specifications.',
    };
  }

  // 5. Default General Attribution (Revenue / Margin Contribution)
  return {
    sourceTable: 'sales_order_line',
    sourceTableLabel: 'sales_order_line (Sales Order Fulfillments)',
    filterConditions: [
      `material_id = '${mat}'`,
      `order_date BETWEEN '${actualPeriod}'`,
    ],
    inputFields: [
      {
        field: 'gross_revenue',
        label: 'Gross Invoiced Revenue',
        description: 'Billed sales order revenue net of contractual discounts',
      },
      {
        field: 'cogs_amount',
        label: 'Cost of Goods Sold (COGS)',
        description: 'Standard and actual inventory costs relieved upon customer delivery',
      },
    ],
    formula: '((SUM(gross_revenue) - SUM(cogs_amount)) / SUM(gross_revenue)) * 100%',
    formulaDescription: 'Gross Margin Spread = (Gross Revenue - COGS) ÷ Gross Revenue × 100%',
    format: 'percentage',
    explanation:
      'Extracts realized billing revenue and outbound dispatch COGS to evaluate the overall gross margin contribution spread.',
  };
}

/**
 * Simulates query execution and returns evaluated formatted value
 */
export function executeSemanticQuery(plan: SemanticExecutionPlan): {
  value: string;
  unit: string;
} {
  switch (plan.format) {
    case 'percentage':
      if (plan.sourceTable === 'purchase_order_line') {
        return { value: '+14.2%', unit: '%' };
      }
      if (plan.sourceTable === 'inventory_usage') {
        return { value: '+6.8%', unit: '%' };
      }
      return { value: '-2.4%', unit: '%' };
    case 'currency':
      if (plan.sourceTable === 'production') {
        return { value: '$18,450.00', unit: 'USD' };
      }
      if (plan.sourceTable === 'bill_of_material') {
        return { value: '+$4,320.00', unit: 'USD' };
      }
      return { value: '$12,800.00', unit: 'USD' };
    case 'number':
      return { value: '342', unit: 'units' };
    default:
      return { value: '1.24', unit: '' };
  }
}
