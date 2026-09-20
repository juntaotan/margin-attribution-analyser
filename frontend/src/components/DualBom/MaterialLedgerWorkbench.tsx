import React, { useState } from 'react';
import { Download, AlertCircle, ChevronLeft, ChevronRight } from 'lucide-react';
import { MaterialLedgerItem, DualBomNode, formatCurrency, formatQty } from '../../varianceEngine';

interface MaterialLedgerWorkbenchProps {
  items: MaterialLedgerItem[];
  selectedNodeId: string | null;
  onInspectNode: (node: DualBomNode) => void;
}

export const MaterialLedgerWorkbench: React.FC<MaterialLedgerWorkbenchProps> = ({
  items,
  selectedNodeId,
  onInspectNode,
}) => {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [currentPage, setCurrentPage] = useState<number>(1);
  const pageSize = 10;

  const toggleSelectAll = () => {
    if (selectedIds.size === items.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(items.map((i) => i.id)));
    }
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const paginatedItems = items.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  const selectedItemName = items.find((i) => i.id === selectedNodeId)?.materialCode;

  return (
    <section className="w-full h-full bg-white flex flex-col overflow-hidden">
      {/* Table Toolbar */}
      <div className="px-4 py-2 bg-slate-100/70 border-b border-slate-200 flex flex-wrap items-center justify-between gap-y-2 shrink-0">
        <div className="flex items-center gap-2">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-700">
            Production Material Ledger Workbench
          </span>
          <span className="text-[10px] font-mono bg-white px-2 py-0.5 rounded border border-slate-200 text-slate-500">
            {items.length} Line Items Traced
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            className="h-6 px-2.5 bg-white hover:bg-slate-50 border border-slate-200 text-slate-700 rounded text-xs flex items-center gap-1.5 transition-colors shadow-2xs"
          >
            <Download className="w-3 h-3 text-slate-500" />
            <span>Export Audit Ledger</span>
          </button>
        </div>
      </div>

      {/* Ledger Table */}
      <div className="overflow-x-auto overflow-y-auto w-full flex-1 min-h-0">
        <table className="w-full text-left border-collapse text-xs">
          <thead>
            <tr className="bg-slate-50 border-b border-slate-200 text-[10px] font-bold uppercase tracking-wider text-slate-500 select-none">
              <th className="w-8 py-2 px-3 text-center">
                <input
                  type="checkbox"
                  checked={selectedIds.size > 0 && selectedIds.size === items.length}
                  onChange={toggleSelectAll}
                  className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-0 cursor-pointer"
                />
              </th>
              <th className="py-2 px-3">Work Order</th>
              <th className="py-2 px-3">Part / Material Code</th>
              <th className="py-2 px-3">Material Description</th>
              <th className="py-2 px-3">Batch / Lot #</th>
              <th className="py-2 px-3">Station</th>
              <th className="py-2 px-3 text-right">BOM Qty</th>
              <th className="py-2 px-3 text-right">Actual Qty</th>
              <th className="py-2 px-3 text-right">Unit Cost</th>
              <th className="py-2 px-3 text-right">Total Cost</th>
              <th className="py-2 px-3 text-right">Variance Δ</th>
              <th className="py-2 px-3 text-center">Status</th>
              <th className="py-2 px-3 text-center">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 font-mono text-xs">
            {paginatedItems.map((item) => {
              const isSelected = selectedNodeId === item.id || selectedIds.has(item.id);

              let rowClass = 'hover:bg-slate-50 transition-colors';
              let statusBadgeClass = 'bg-slate-100 text-slate-600 border border-slate-200';

              if (item.status === 'Over-issued') {
                rowClass = 'bg-rose-50/40 hover:bg-rose-50/70 transition-colors';
                statusBadgeClass = 'bg-rose-600 text-white font-bold';
              } else if (item.status === 'Scrap Extra') {
                rowClass = 'bg-rose-50/20 hover:bg-rose-50/40 transition-colors';
                statusBadgeClass = 'bg-rose-100 text-rose-700 border border-rose-200 font-semibold';
              } else if (item.status === 'Released') {
                rowClass = 'bg-emerald-50/30 hover:bg-emerald-50/50 transition-colors';
                statusBadgeClass = 'bg-emerald-100 text-emerald-800 border border-emerald-200 font-semibold';
              } else if (item.status === 'Over-cost') {
                rowClass = 'bg-amber-50/20 hover:bg-amber-50/40 transition-colors';
                statusBadgeClass = 'bg-amber-100 text-amber-800 border border-amber-200 font-semibold';
              }

              if (isSelected) {
                rowClass += ' ring-1 ring-blue-500 bg-blue-50/20';
              }

              return (
                <tr key={item.id} className={rowClass}>
                  <td className="py-2 px-3 text-center">
                    <input
                      type="checkbox"
                      checked={selectedIds.has(item.id)}
                      onChange={() => toggleSelect(item.id)}
                      className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-0 cursor-pointer"
                    />
                  </td>
                  <td className="py-2 px-3 font-medium text-blue-700">
                    {item.workOrder}
                  </td>
                  <td className="py-2 px-3 font-bold text-slate-800">
                    <div className="flex items-center gap-1">
                      {item.severity === 'major' && (
                        <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />
                      )}
                      <span>{item.materialCode}</span>
                    </div>
                  </td>
                  <td className="py-2 px-3 font-sans text-slate-800 font-medium">
                    {item.description}
                  </td>
                  <td className="py-2 px-3 text-slate-500">
                    {item.batchLot}
                  </td>
                  <td className="py-2 px-3 text-slate-700 font-sans font-medium">
                    {item.station}
                  </td>
                  <td className="py-2 px-3 text-right text-slate-500">
                    {formatQty(item.bomQty)}
                  </td>
                  <td
                    className={`py-2 px-3 text-right font-bold ${
                      item.actualQty > item.bomQty ? 'text-rose-600' : 'text-slate-800'
                    }`}
                  >
                    {formatQty(item.actualQty)}
                  </td>
                  <td className="py-2 px-3 text-right text-slate-600">
                    {formatCurrency(item.unitCost)}
                  </td>
                  <td className="py-2 px-3 text-right font-semibold text-slate-900">
                    {formatCurrency(item.totalCost)}
                  </td>
                  <td
                    className={`py-2 px-3 text-right font-bold ${
                      item.varianceDelta && item.varianceDelta > 0
                        ? 'text-rose-600'
                        : item.varianceDelta && item.varianceDelta < 0
                        ? 'text-emerald-600'
                        : 'text-slate-500'
                    }`}
                  >
                    {formatCurrency(item.varianceDelta)}
                  </td>
                  <td className="py-2 px-3 text-center">
                    <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] ${statusBadgeClass}`}>
                      [{item.status}]
                    </span>
                  </td>
                  <td className="py-2 px-3 text-center">
                    <div className="flex items-center justify-center gap-1 font-sans">
                      <button
                        type="button"
                        onClick={() => onInspectNode(item.node)}
                        className="px-2 py-0.5 bg-white border border-blue-500 text-blue-600 hover:bg-blue-600 hover:text-white rounded text-[10px] font-medium transition-colors shadow-2xs"
                      >
                        Inspect &amp; Trace
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
            {paginatedItems.length === 0 && (
              <tr>
                <td colSpan={13} className="py-8 text-center text-slate-400 font-sans">
                  No material ledger records found for this period.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination Footer */}
      <div className="px-4 py-1.5 bg-slate-50 border-t border-slate-200 flex items-center justify-between text-xs text-slate-500 font-sans">
        <div className="flex items-center gap-2">
          <span>
            Displaying {paginatedItems.length} of {items.length} ledger line-items
          </span>
          <span className="text-slate-300">|</span>
          <span className="font-mono text-slate-700 font-medium">
            Active: {selectedItemName || 'None selected'}
          </span>
        </div>

        {totalPages > 1 && (
          <div className="flex items-center gap-1 text-xs">
            <button
              type="button"
              disabled={currentPage <= 1}
              onClick={() => setCurrentPage((p) => p - 1)}
              className="px-2 py-0.5 border border-slate-200 rounded bg-white disabled:opacity-40"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
            </button>
            <span className="px-2 py-0.5 bg-blue-600 text-white rounded font-mono font-medium">
              {currentPage} / {totalPages}
            </span>
            <button
              type="button"
              disabled={currentPage >= totalPages}
              onClick={() => setCurrentPage((p) => p + 1)}
              className="px-2 py-0.5 border border-slate-200 rounded bg-white disabled:opacity-40"
            >
              <ChevronRight className="w-3.5 h-3.5" />
            </button>
          </div>
        )}
      </div>
    </section>
  );
};
