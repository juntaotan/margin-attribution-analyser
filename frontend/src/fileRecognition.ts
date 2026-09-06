export const MODULE_LABELS = {
  production: 'Production Module',
  sales: 'Sales Module',
  procurement: 'Procurement Module',
  finance: 'Finance Module',
} as const;

export type BusinessModule = keyof typeof MODULE_LABELS;

// Canonical tables, enum names and aliases from SchemaMappingPresetCatalog.
export const TABLE_PRESETS = [
  { table: 'production_order', module: 'production', aliases: ['production', '生产', '生产明细', '生产订单'] },
  { table: 'material_consumption', module: 'production', aliases: ['物料消耗', '材料消耗', '生产领料'] },
  { table: 'inventory_usage', module: 'production', aliases: ['inventory_movement', '库存移动', '库存流水', '出入库明细'] },
  { table: 'bill_of_material', module: 'production', aliases: ['bom', '物料清单', '产品配方'] },
  { table: 'sales_order', module: 'sales', aliases: ['sales', '销售', '销售明细', '销售订单'] },
  { table: 'purchases', module: 'procurement', aliases: ['purchase', '采购', '采购明细', '采购订单'] },
  { table: 'account_receivables', module: 'finance', aliases: ['account_receivable', '应收', '应收账款', '应收明细'] },
  { table: 'account_payables', module: 'finance', aliases: ['account_payable', '应付', '应付账款', '应付明细'] },
] as const satisfies readonly { table: string; module: BusinessModule; aliases: readonly string[] }[];

export type TableName = typeof TABLE_PRESETS[number]['table'];
export type FileRecognition = { table: TableName; module: BusinessModule };

// Mirrors the catalog's lowercase + letter/digit-only normalization.
const normalize = (value: string) => value.trim().toLowerCase().replace(/[^\p{L}\p{Nd}]/gu, '');

// Strips common export noise (e.g. "bom-test", "sales_order_20260906", "order (1)")
const cleanStem = (raw: string): string => {
  return raw
    .replace(/\s*\(\d+\)$/, '')
    .replace(/[-_ ]?(test|sample|demo|mock|backup|copy|template|temp|tmp)$/i, '')
    .replace(/[-_ ]?\d{4}([-_]?\d{2}([-_]?\d{2})?)?$/, '')
    .replace(/^(\d{4}([-_]?\d{2}([-_]?\d{2})?)?|\d{4,8})[-_ ]?/, '')
    .replace(/^(export|data|temp|tmp|test)[-_ ]?/i, '')
    .trim();
};

export function recognizeFileName(fileName: string): FileRecognition | null {
  if (!/\.(csv|xlsx|xls)$/i.test(fileName)) return null;
  const rawStem = fileName.replace(/\.[^.]+$/, '').trim();
  const stem = normalize(rawStem);
  if (!stem) return null;

  // 1. Exact normalized match
  const exactMatches = TABLE_PRESETS.filter(({ table, aliases }) =>
    [table, ...aliases].some((alias) => normalize(alias) === stem),
  );
  if (exactMatches.length === 1) {
    return { table: exactMatches[0].table, module: exactMatches[0].module };
  }

  // 2. Cleaned stem match (stripping common suffixes/dates/test tags)
  const cleaned = cleanStem(rawStem);
  if (cleaned && cleaned !== rawStem) {
    const cleanedNormalized = normalize(cleaned);
    const cleanedMatches = TABLE_PRESETS.filter(({ table, aliases }) =>
      [table, ...aliases].some((alias) => normalize(alias) === cleanedNormalized),
    );
    if (cleanedMatches.length === 1) {
      return { table: cleanedMatches[0].table, module: cleanedMatches[0].module };
    }
  }

  return null;
}

