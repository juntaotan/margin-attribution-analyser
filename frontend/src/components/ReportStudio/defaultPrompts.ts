export interface PreconfiguredPromptDefinition {
  key: string;
  label: string;
  prompt: string;
  format: 'currency' | 'percentage' | 'number' | 'text';
  description: string;
}

export const PRECONFIGURED_PROMPTS: Record<string, PreconfiguredPromptDefinition> = {
  Revenue: {
    key: 'Revenue',
    label: 'Sales Revenue',
    prompt: 'Query sales_details to calculate sum(total_revenue) for the active period',
    format: 'currency',
    description: 'Sums recorded sales revenue for the active period.',
  },
  'Revenue Change %': {
    key: 'Revenue Change %',
    label: 'Revenue Growth Rate',
    prompt: 'Compare sum(total_revenue) from sales_details across current and baseline periods',
    format: 'percentage',
    description: 'Compares recorded revenue across the two periods.',
  },
  'Gross Margin': {
    key: 'Gross Margin',
    label: 'Gross Margin',
    prompt: 'Query sales_details to calculate sum(total_revenue - total_cost) for the active period',
    format: 'currency',
    description: 'Subtracts recorded product cost from revenue.',
  },
  'Gross Margin %': {
    key: 'Gross Margin %',
    label: 'Gross Margin Rate',
    prompt:
      'Query sales_details to calculate round(sum(total_revenue - total_cost) / nullif(sum(total_revenue), 0) * 100, 2)',
    format: 'percentage',
    description: 'Calculates gross margin as a share of revenue.',
  },
  'Cost of Goods Sold': {
    key: 'Cost of Goods Sold',
    label: 'Cost of Goods Sold',
    prompt: 'Query sales_details to calculate sum(total_cost) for the active period',
    format: 'currency',
    description: 'Sums product costs recorded in the active period.',
  },
  'Current Period': {
    key: 'Current Period',
    label: 'Active Reporting Period',
    prompt: 'Extract the active reporting cycle period from sales_details date range',
    format: 'text',
    description: 'Identifies the start and end dates of the active analysis cycle.',
  },
  'Comparison Period': {
    key: 'Comparison Period',
    label: 'Comparable Baseline Period',
    prompt: 'Extract the comparable baseline cycle period from historical sales_details date range',
    format: 'text',
    description: 'Identifies the start and end dates of the historical baseline comparison cycle.',
  },
  'PPV Variance': {
    key: 'PPV Variance',
    label: 'Purchase Price Variance',
    prompt:
      'Query actual purchase order settlement prices vs baseline standard costs to derive Purchase Price Variance rate',
    format: 'percentage',
    description: 'Evaluates procurement deviations between cleared purchase prices and frozen baseline standards.',
  },
  'Usage Variance': {
    key: 'Usage Variance',
    label: 'Material Overuse Rate',
    prompt:
      'Query inventory_usage to calculate total material consumption: sum(material_total_cost) for the active period',
    format: 'currency',
    description: 'Calculates total inventory material usage cost for the active reporting period.',
  },
  'Scrap Loss': {
    key: 'Scrap Loss',
    label: 'Scrap Financial Impact',
    prompt: 'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
    format: 'currency',
    description: 'Summarizes net scrap write-off costs and production yield concession losses.',
  },
  'ECN Impact': {
    key: 'ECN Impact',
    label: 'Structural ECN Cost Impact',
    prompt: 'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
    format: 'currency',
    description: 'Quantifies structural BOM cost drift caused by Engineering Change Notices.',
  },
};

/**
 * Normalizes a key (case-insensitive, strips spaces and special chars) to find preconfigured prompts.
 */
export const getPreconfiguredPrompt = (rawKey?: string): PreconfiguredPromptDefinition | undefined => {
  if (!rawKey || !rawKey.trim()) return undefined;

  const direct = PRECONFIGURED_PROMPTS[rawKey.trim()];
  if (direct) return direct;

  const normalized = rawKey.toLowerCase().replace(/[^a-z0-9]/g, '');

  for (const [key, def] of Object.entries(PRECONFIGURED_PROMPTS)) {
    const keyNorm = key.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (keyNorm === normalized) return def;
  }

  // Common aliases
  if (normalized.includes('revenuechange') || normalized.includes('revenuegrowth')) {
    return PRECONFIGURED_PROMPTS['Revenue Change %'];
  }
  if (normalized.includes('grossmarginpercent') || normalized.includes('grossmarginrate')) {
    return PRECONFIGURED_PROMPTS['Gross Margin %'];
  }
  if (normalized.includes('grossmargin')) {
    return PRECONFIGURED_PROMPTS['Gross Margin'];
  }
  if (normalized.includes('currentperiod') || normalized.includes('reportingperiod')) {
    return PRECONFIGURED_PROMPTS['Current Period'];
  }
  if (normalized.includes('comparisonperiod') || normalized.includes('baselineperiod')) {
    return PRECONFIGURED_PROMPTS['Comparison Period'];
  }
  if (normalized.includes('ppv') || normalized.includes('purchaseprice')) {
    return PRECONFIGURED_PROMPTS['PPV Variance'];
  }
  if (normalized.includes('usage') || normalized.includes('overuse')) {
    return PRECONFIGURED_PROMPTS['Usage Variance'];
  }
  if (normalized.includes('scrap')) {
    return PRECONFIGURED_PROMPTS['Scrap Loss'];
  }
  if (normalized.includes('ecn') || normalized.includes('designbom')) {
    return PRECONFIGURED_PROMPTS['ECN Impact'];
  }

  return undefined;
};
