export const MODULE_LABELS = {
  production: 'Production Module',
  sales: 'Sales Module',
  procurement: 'Procurement Module',
  finance: 'Finance Module',
} as const;

export type BusinessModule = keyof typeof MODULE_LABELS;

// Canonical tables, enum names and aliases from SchemaMappingPresetCatalog.
export const TABLE_PRESETS = [
  { table: 'material_master', module: 'production', aliases: ['materials', 'material', '物料', '物料明细', '物料主数据', '物料档案'] },
  { table: 'inventory_usage', module: 'production', aliases: ['inventory_movement', '库存移动', '库存流水', '出入库明细', 'material_consumption', '物料消耗', '材料消耗', '生产领料'] },
  { table: 'bill_of_material', module: 'production', aliases: ['bom', '物料清单', '产品配方'] },
  { table: 'sales_details', module: 'sales', aliases: ['sales_detail', 'sales details', '销售明细', '销售收入成本明细'] },
  { table: 'purchases', module: 'procurement', aliases: ['purchase', '采购', '采购明细', '采购订单'] },
] as const satisfies readonly { table: string; module: BusinessModule; aliases: readonly string[] }[];

export type TableName = typeof TABLE_PRESETS[number]['table'];
export type FileRecognition = { table: TableName; module: BusinessModule };

// Mirrors the catalog's lowercase + letter/digit-only normalization.
const normalize = (value: string) => value.trim().toLowerCase().replace(/[^\p{L}\p{Nd}]/gu, '');

// Strips common export noise (e.g. "bom-test", "sales_details_20260906", "order (1)")
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
  if (!/\.(xlsx|xls)$/i.test(fileName)) return null;
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
