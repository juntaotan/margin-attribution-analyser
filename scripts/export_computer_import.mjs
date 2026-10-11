/** Convert the five generated import CSVs into typed, single-sheet XLSX files.
 * Requires @oai/artifact-tool from the configured artifact runtime. Original
 * decimal strings are retained in input.json for independent database checks.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const [source, destination] = process.argv.slice(2);
if (!source || !destination) throw new Error('Usage: export_computer_import.mjs SOURCE_DIRECTORY EMPTY_OUTPUT_DIRECTORY');
await fs.mkdir(destination, { recursive: true });
if ((await fs.readdir(destination)).length) throw new Error('Output directory must be empty');
const names = ['material_master', 'bill_of_material', 'inventory_usage', 'purchases', 'sales_details'];
const numeric = new Set(['unit_cost', 'material_usage', 'product_num', 'material_num',
  'product_total_cost', 'material_total_cost', 'product_total_price', 'total_revenue', 'total_cost']);
const fixture = {};
for (const name of names) {
  const workbook = await Workbook.fromCSV(await fs.readFile(path.join(source, `${name}.csv`), 'utf8'), { sheetName: name });
  const sheet = workbook.worksheets.getItemAt(0);
  const original = sheet.getUsedRange().values;
  const headers = original[0];
  const rows = original.slice(1);
  fixture[name] = { headers, rows };
  const typed = rows.map(row => row.map((value, c) => {
    if (value == null || value === '') return null;
    // Numeric Excel dates remain compatible with the application's POI schema;
    // ISO-date cells (t="d") are not supported by its transitional OOXML parser.
    if (headers[c] === 'date') return Math.round((Date.parse(`${value}T00:00:00Z`) - Date.UTC(1899, 11, 30)) / 86400000);
    return numeric.has(headers[c]) ? Number(value) : value;
  }));
  sheet.getRangeByIndexes(1, 0, rows.length, headers.length).values = typed;
  sheet.showGridLines = false;
  sheet.freezePanes.freezeRows(1);
  sheet.getRangeByIndexes(0, 0, rows.length + 1, headers.length).format.font = { name: 'Arial', size: 10 };
  sheet.getRangeByIndexes(0, 0, 1, headers.length).format = {
    fill: '#263B53', font: { bold: true, color: '#FFFFFF' }, rowHeight: 24,
  };
  for (let c = 0; c < headers.length; c++) {
    const column = sheet.getRangeByIndexes(0, c, rows.length + 1, 1);
    const width = Math.max(headers[c].length + 3, ...rows.map(row => String(row[c] ?? '').length + 2));
    column.format.columnWidth = Math.min(125, Math.max(18, width));
    if (numeric.has(headers[c])) sheet.getRangeByIndexes(1, c, rows.length, 1).setNumberFormat('0.000000');
    if (headers[c] === 'date') sheet.getRangeByIndexes(1, c, rows.length, 1).setNumberFormat('yyyy-mm-dd');
  }
  workbook.recalculate();
  const checked = await workbook.inspect({ kind: 'table', range: `${name}!A1:${String.fromCharCode(64 + headers.length)}4`,
    include: 'values,formulas', tableMaxRows: 4, tableMaxCols: headers.length, maxChars: 1200 });
  console.log(name, rows.length, checked.ndjson);
  const preview = await workbook.render({ sheetName: name, range: `A1:${String.fromCharCode(64 + headers.length)}4`, scale: 1 });
  await fs.writeFile(path.join(destination, `${name}-preview.png`), new Uint8Array(await preview.arrayBuffer()));
  await (await SpreadsheetFile.exportXlsx(workbook)).save(path.join(destination, `${name}.xlsx`));
}
await fs.writeFile(path.join(destination, 'input.json'), JSON.stringify(fixture));
