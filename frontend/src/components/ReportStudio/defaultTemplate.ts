import { ReportDocument } from './types';
import { parseSemanticPrompt, executeSemanticQuery } from './semanticParser';

export const createDefaultDocument = (): ReportDocument => {
  const context = {
    materialId: 'MTR-1029 (Stator Punching Lamination)',
    actualStartDate: '2026-01-01',
    actualEndDate: '2026-01-31',
    comparableStartDate: '2025-01-01',
    comparableEndDate: '2025-01-31',
  };

  // Pre-seed 4 representative financial placeholder tokens
  const plan1 = parseSemanticPrompt(
    'Query actual purchase order settlement prices vs baseline standard costs to derive PPV deviation rate',
    context
  );
  const res1 = executeSemanticQuery(plan1);

  const plan2 = parseSemanticPrompt(
    'Analyze shop-floor material dispatches against work order planned BOM quotas to compute overuse rate',
    context
  );
  const res2 = executeSemanticQuery(plan2);

  const plan3 = parseSemanticPrompt(
    'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
    context
  );
  const res3 = executeSemanticQuery(plan3);

  const plan4 = parseSemanticPrompt(
    'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
    context
  );
  const res4 = executeSemanticQuery(plan4);

  return {
    id: 'doc-commentary-001',
    title: 'Management Commentary: Gross Margin Performance & Root-Cause Synthesis',
    periodActual: '2026-01-01 to 2026-01-31',
    periodComparable: '2025-01-01 to 2025-01-31',
    materialFocus: 'MTR-1029 (Stator Punching Lamination - 0.35mm Electrical Steel)',
    blocks: [
      {
        id: 'b1',
        type: 'h1',
        content: 'Executive Briefing: High-Runner Component Margin Erosion & Dual-BOM Attribution',
      },
      {
        id: 'b2',
        type: 'paragraph',
        content:
          'During the current audit cycle, reconciliation across the Dual-BOM financial topology identified significant gross margin contraction driven by key component MTR-1029 (Stator Punching Lamination). To isolate the structural drivers behind this deviation, our attribution engine decoupled variance across procurement pricing, shop-floor consumption, and engineering change notices (ECN).',
      },
      {
        id: 'b3',
        type: 'divider',
        content: '',
      },
      {
        id: 'b4',
        type: 'h2',
        content: '1. Purchase Price Variance (PPV) & Supplier Inflation',
      },
      {
        id: 'b5',
        type: 'paragraph',
        content:
          'Benchmarking cleared purchase invoices against frozen baseline standard costs indicates an effective Purchase Price Variance rate of {{token_ppv}}. This cost expansion was primarily driven by spot market surcharges on electrical steel commodities and an increase in expedited spot buys from 5% to 18% of total volume.',
      },
      {
        id: 'b6',
        type: 'h2',
        content: '2. Shop-Floor Material Usage & Yield Efficiency',
      },
      {
        id: 'b7',
        type: 'paragraph',
        content:
          'On the manufacturing floor, actual physical dispatches exceeded planned production order allowances, resulting in a net material overuse rate of {{token_overuse}}. Slippage was concentrated in Line 2 stamping recalibration, where tool alignment runs exceeded standard scrap quotas.',
      },
      {
        id: 'b8',
        type: 'paragraph',
        content:
          'Direct quality failure analysis: aggregate stamping burr and lamination stacking defects generated a net scrap loss of {{token_scrap}}. Process engineering has scheduled a corrective action review to restore press tolerance.',
        highlight: true, // Yellow marker highlight
      },
      {
        id: 'b9',
        type: 'divider',
        content: '',
      },
      {
        id: 'b10',
        type: 'h2',
        content: '3. Engineering BOM Revisions & Structural Design Drift',
      },
      {
        id: 'b11',
        type: 'paragraph',
        content:
          'In engineering design, revision ECN-2026-08 introduced reinforced thermal stack requirements, increasing theoretical lamination usage by 2 sheets per sub-assembly. The resulting structural BOM quota variance stands at {{token_ecn}}.',
        bold: true,
      },
      {
        id: 'b12',
        type: 'paragraph',
        content:
          'Actionable Recommendations: 1) Supply Chain to finalize long-term index contracts for electrical steel before Q4; 2) Manufacturing Engineering to complete stamping die recutting by Friday to suppress overuse below 2.5%.',
      },
    ],
    tokens: {
      'Revenue': {
        id: 'Revenue',
        label: 'Net Sales Revenue',
        prompt: 'Query actual sales orders to calculate total sales revenue sum(product_total_price) for the active period',
        status: 'draft',
        resolvedValue: '$12,000.00',
        unit: '$',
      },
      'Revenue Change %': {
        id: 'Revenue Change %',
        label: 'Revenue Growth Rate',
        prompt: 'Calculate sales revenue percentage growth rate comparing current sales_order product_total_price against baseline comparison period',
        status: 'draft',
        resolvedValue: '0.0%',
        unit: '%',
      },
      'Gross Margin': {
        id: 'Gross Margin',
        label: 'Total Gross Margin',
        prompt: 'Query order_margin_summary to calculate total gross margin contribution sum(gross_margin) for the active period',
        status: 'draft',
        resolvedValue: '$7,100.00',
        unit: '$',
      },
      'Gross Margin %': {
        id: 'Gross Margin %',
        label: 'Gross Margin Rate',
        prompt: 'Query order_margin_summary to compute overall gross margin percentage: round(sum(gross_margin) / nullif(sum(revenue), 0) * 100, 2)',
        status: 'draft',
        resolvedValue: '59.2%',
        unit: '%',
      },
      'Current Period': {
        id: 'Current Period',
        label: 'Active Reporting Period',
        prompt: 'Extract the active reporting cycle period from sales orders date range',
        status: 'draft',
        resolvedValue: '2026-01-01 to 2026-01-31',
      },
      'Comparison Period': {
        id: 'Comparison Period',
        label: 'Comparable Baseline Period',
        prompt: 'Extract the comparable baseline cycle period from historical sales orders date range',
        status: 'draft',
        resolvedValue: '2025-01-01 to 2025-01-31',
      },
      token_ppv: {
        id: 'token_ppv',
        label: 'PPV Deviation Rate',
        prompt:
          'Query actual purchase order settlement prices vs baseline standard costs to derive PPV deviation rate',
        status: 'executed',
        semanticPlan: plan1,
        resolvedValue: res1.value,
        unit: res1.unit,
        updatedAt: '2026-08-31 16:30',
      },
      'PPV Variance': {
        id: 'PPV Variance',
        label: 'PPV Deviation Rate',
        prompt:
          'Query actual purchase order settlement prices vs baseline standard costs to derive PPV deviation rate',
        status: 'executed',
        semanticPlan: plan1,
        resolvedValue: res1.value,
        unit: res1.unit,
        updatedAt: '2026-08-31 16:30',
      },
      token_overuse: {
        id: 'token_overuse',
        label: 'Material Overuse Rate',
        prompt:
          'Analyze shop-floor material dispatches against work order planned BOM quotas to compute overuse rate',
        status: 'executed',
        semanticPlan: plan2,
        resolvedValue: res2.value,
        unit: res2.unit,
        updatedAt: '2026-08-31 16:32',
      },
      'Usage Variance': {
        id: 'Usage Variance',
        label: 'Material Overuse Rate',
        prompt:
          'Analyze shop-floor material dispatches against work order planned BOM quotas to compute overuse rate',
        status: 'executed',
        semanticPlan: plan2,
        resolvedValue: res2.value,
        unit: res2.unit,
        updatedAt: '2026-08-31 16:32',
      },
      token_scrap: {
        id: 'token_scrap',
        label: 'Scrap Financial Impact',
        prompt:
          'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
        status: 'executed',
        semanticPlan: plan3,
        resolvedValue: res3.value,
        unit: res3.unit,
        updatedAt: '2026-08-31 16:35',
      },
      'Scrap Loss': {
        id: 'Scrap Loss',
        label: 'Scrap Financial Impact',
        prompt:
          'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
        status: 'executed',
        semanticPlan: plan3,
        resolvedValue: res3.value,
        unit: res3.unit,
        updatedAt: '2026-08-31 16:35',
      },
      token_ecn: {
        id: 'token_ecn',
        label: 'Structural ECN Cost Impact',
        prompt:
          'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
        status: 'executed',
        semanticPlan: plan4,
        resolvedValue: res4.value,
        unit: res4.unit,
        updatedAt: '2026-08-31 16:40',
      },
      'ECN Impact': {
        id: 'ECN Impact',
        label: 'Structural ECN Cost Impact',
        prompt:
          'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
        status: 'executed',
        semanticPlan: plan4,
        resolvedValue: res4.value,
        unit: res4.unit,
        updatedAt: '2026-08-31 16:40',
      },
    },
  };
};
