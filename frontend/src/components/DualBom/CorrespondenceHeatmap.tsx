import React, { useMemo, useState } from 'react';
import { correspondenceValue, recorded, type FgwResult, type ProductionTree } from '../../analysisRun';

const RAMP = ['#F3F6F9', '#CEDCE8', '#91ACC4', '#557A9B', '#284D6B'];
export const CorrespondenceHeatmap: React.FC<{ result: FgwResult; left: ProductionTree; right: ProductionTree }> = ({ result, left, right }) => {
  const [mode, setMode] = useState<'share' | 'mass'>('share');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<[number, number] | null>(null);
  const [table, setTable] = useState(false);
  const labels = useMemo(() => ({ rows: result.left.nodes, columns: result.right.nodes }), [result]);
  const query = search.trim().toLowerCase();
  const matches = (node: { materialId: string; occurrenceId: string }, production: string) =>
    `${node.materialId} ${node.occurrenceId} ${production}`.toLowerCase().includes(query);
  const rows = labels.rows.map((node, i) => ({ node, i })).filter(({ node }) => matches(node, left.productionId));
  const columns = labels.columns.map((node, i) => ({ node, i })).filter(({ node }) => matches(node, right.productionId));
  const ceiling = mode === 'share' ? 1 : Math.max(...result.p);
  const selectedMass = selected ? correspondenceValue(result, selected[0], selected[1], 'mass') : null;
  const selectedShare = selected ? correspondenceValue(result, selected[0], selected[1], 'share') : null;
  const nodeFor = (tree: ProductionTree, id: string) => tree.nodes.find(node => node.occurrenceId === id);
  const label = (node: { materialId: string; occurrenceId: string }) => `${node.materialId} · ${node.occurrenceId}`;
  const valueText = (value: number | null) => value == null ? 'Unavailable' : mode === 'share' ? `${(value * 100).toPrecision(4)}%` : value.toPrecision(6);
  return <div className="space-y-3">
    <div className="flex flex-wrap items-center gap-3">
      <label className="text-xs">Find occurrence <input className="ml-2 border rounded px-2 py-1" aria-label="Find heatmap occurrence" value={search} onChange={e => setSearch(e.target.value)} /></label>
      <label className="text-xs">Values <select className="ml-2 border rounded p-1" value={mode} onChange={e => setMode(e.target.value as 'share' | 'mass')}><option value="share">Baseline row share</option><option value="mass">Raw correspondence mass</option></select></label>
      <button className="border rounded px-2 py-1 text-xs" onClick={() => setTable(!table)}>{table ? 'Show heatmap' : 'Show equivalent table'}</button>
    </div>
    <p className="text-xs text-slate-600">{mode === 'share' ? 'Row share P[i,j] / p[i]: 0–100%' : `Raw mass P[i,j]: 0–${ceiling.toPrecision(4)}`} · Higher intensity means greater correspondence weight, not confidence or causation.</p>
    <div className="flex items-center gap-1 text-xs" aria-label="Heatmap legend">0 {RAMP.map(colour => <span key={colour} style={{ background: colour }} className="w-8 h-3 border border-slate-300" />)} {mode === 'share' ? '100%' : ceiling.toPrecision(4)}</div>
    <p className="text-xs text-slate-600">Showing {rows.length}/{result.rows} rows and {columns.length}/{result.columns} columns. Filtering retains the original row denominators.</p>
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_260px]">
      <div className="overflow-auto max-h-[48vh] border rounded-lg" tabIndex={0} aria-label="Correspondence matrix viewport">
        <table className="text-xs border-collapse w-max min-w-full">
          <caption className="sr-only">Baseline occurrences in rows; comparison occurrences in columns</caption>
          <thead><tr><th className="sticky top-0 left-0 bg-white z-20 border p-2">{left.productionId} ↓ / {right.productionId} →</th>{columns.map(({ node, i }) => <th key={node.occurrenceId} scope="col" title={node.occurrenceId} className="sticky top-0 z-10 bg-white border p-2 max-w-[130px]"><span className="block">{node.materialId}</span><span className="font-normal text-slate-600">#{i} · {right.productionId}</span></th>)}</tr></thead>
          <tbody>{rows.map(({ node, i }) => <tr key={node.occurrenceId}><th scope="row" title={node.occurrenceId} className="sticky left-0 bg-white z-10 border p-2 text-left max-w-[180px]"><span className="block">{node.materialId}</span><span className="font-normal text-slate-600">#{i} · {left.productionId}</span></th>{columns.map(({ node: column, i: j }) => {
            const value = correspondenceValue(result, i, j, mode);
            const colour = value == null ? '#FFFFFF' : RAMP[Math.min(4, Math.floor(Math.max(0, value / ceiling) * 4))];
            return <td key={column.occurrenceId} className="border p-0"><button type="button" className={`heatmap-cell min-w-[72px] min-h-[44px] w-full p-2 ${selected?.[0] === i && selected[1] === j ? 'ring-2 ring-inset ring-slate-900' : ''}`} style={{ background: table ? '#FFFFFF' : colour, color: !table && (colour === RAMP[3] || colour === RAMP[4]) ? '#FFFFFF' : '#1E293B' }}
              aria-label={`${label(node)} to ${label(column)}: ${valueText(value)}`} aria-pressed={selected?.[0] === i && selected[1] === j}
              title={`P[${i},${j}] = ${correspondenceValue(result, i, j, 'mass') ?? 'Unavailable'}`}
              onClick={() => setSelected([i, j])}>{valueText(value)}</button></td>;
          })}</tr>)}</tbody>
        </table>
        {(!rows.length || !columns.length) && <p className="p-4 text-xs">No occurrences match this filter.</p>}
      </div>
      <aside className="border rounded-lg p-3 text-xs space-y-2" aria-live="polite">
        <h4 className="font-semibold">Selected correspondence</h4>
        {selected ? <>
          <p className="break-all">{label(labels.rows[selected[0]])} ↔ {label(labels.columns[selected[1]])}</p>
          <p>Raw mass: {selectedMass == null ? 'Unavailable' : selectedMass.toPrecision(10)}</p>
          <p>Row share: {selectedShare == null ? 'Unavailable' : `${(selectedShare * 100).toPrecision(8)}%`}</p>
          {[left, right].map((tree, index) => {
            const id = index === 0 ? labels.rows[selected[0]].occurrenceId : labels.columns[selected[1]].occurrenceId;
            const occurrence = nodeFor(tree, id);
            return <div key={tree.productionId} className="border-t pt-2"><strong>{tree.productionId}</strong><p>Quantity: {recorded(occurrence?.quantity)} {occurrence?.unit}</p><p>Recorded amount: {recorded(occurrence?.amount)}</p><p className="break-all">Source issues: {occurrence?.issueLineIds.join(', ') || 'Unavailable'}</p><p>{occurrence?.statuses.join(', ')}</p></div>;
          })}
        </> : <p>Select a cell to inspect both occurrences and their recorded evidence.</p>}
      </aside>
    </div>
    <details className="border rounded p-3"><summary className="cursor-pointer text-xs font-medium">Production trees and linked occurrences</summary><div className="grid sm:grid-cols-2 gap-3 mt-3">{[left, right].map((tree, side) => <div key={tree.productionId}><h4 className="text-sm font-semibold">{tree.productionId}</h4>{tree.nodes.map(node => <p key={node.occurrenceId} className={`p-1 text-xs break-all ${selected && node.occurrenceId === (side === 0 ? labels.rows[selected[0]].occurrenceId : labels.columns[selected[1]].occurrenceId) ? 'bg-[#E8EFF5] outline outline-1 outline-[#466987]' : ''}`}>{node.index === tree.rootIndex ? 'Root' : 'Occurrence'} #{node.index} · {node.materialId} · {node.occurrenceId}</p>)}<ul className="text-xs mt-2">{tree.edges.map((edge, i) => <li key={i}>#{edge.materialIndex} {tree.nodes[edge.materialIndex]?.materialId} → #{edge.receivingIndex} {tree.nodes[edge.receivingIndex]?.materialId}</li>)}</ul></div>)}</div></details>
  </div>;
};
