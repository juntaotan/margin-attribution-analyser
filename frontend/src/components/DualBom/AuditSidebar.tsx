import React, { useState } from 'react';
import {
  Crosshair,
  AlertTriangle,
  Sparkles,
  ArrowUp,
} from 'lucide-react';
import {
  DualBomNode,
  AuditDossier,
  buildAuditDossier,
  formatCurrency,
  formatQty,
} from '../../varianceEngine';

interface AuditSidebarProps {
  selectedNode: DualBomNode | null;
  allNodes: DualBomNode[];
}

export const AuditSidebar: React.FC<AuditSidebarProps> = ({
  selectedNode,
  allNodes,
}) => {
  const [commandInput, setCommandInput] = useState<string>('');

  if (!selectedNode) {
    return (
      <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200 p-6 text-center justify-center items-center text-slate-400">
        <Crosshair className="w-10 h-10 text-slate-300 stroke-1 mb-2" />
        <p className="text-xs font-semibold text-slate-600">No Node Selected</p>
        <p className="text-[11px] text-slate-400 mt-1 max-w-[220px]">
          Click any component card in the Dual-BOM tree or material ledger table to inspect detailed specifications and causal audit reports.
        </p>
      </aside>
    );
  }

  const dossier: AuditDossier = buildAuditDossier(selectedNode, allNodes);
  const isOverrun = (selectedNode.costDelta ?? 0) > 0;

  const handleQuickCommand = (text: string) => {
    setCommandInput(text);
  };

  return (
    <aside className="w-full lg:w-96 xl:w-96 bg-white flex flex-col shrink-0 border-t lg:border-t-0 border-slate-200 overflow-y-auto">
      {/* ================= Upper 50%: Node Inspector Specification ================= */}
      <div className="p-3.5 border-b border-slate-200 flex flex-col bg-white">
        <div className="flex items-center justify-between border-b border-slate-100 pb-2 mb-2.5">
          <div className="truncate pr-2">
            <span className="font-mono text-xs font-bold text-blue-700 flex items-center gap-1">
              <Crosshair className="w-3.5 h-3.5 text-blue-600" />
              {selectedNode.id} Spec
            </span>
            <h4 className="text-xs font-bold text-slate-900 truncate" title={selectedNode.name}>
              {selectedNode.name}
            </h4>
          </div>
          <span className="font-mono text-[10px] bg-slate-100 px-2 py-0.5 rounded border border-slate-200 text-slate-600 shrink-0">
            {selectedNode.ecn}
          </span>
        </div>

        {/* 4-Grid Metrics */}
        <div className="grid grid-cols-2 gap-2 font-mono text-xs mb-2.5">
          <div className="bg-slate-50 p-2 rounded border border-slate-200">
            <span className="text-slate-400 block text-[10px] uppercase font-bold tracking-wider">
              Standard Qty
            </span>
            <span className="font-semibold text-slate-800">
              {formatQty(selectedNode.standardQty)}
            </span>
          </div>

          <div
            className={`p-2 rounded border ${
              isOverrun
                ? 'bg-rose-50 border-rose-200 text-rose-700'
                : 'bg-emerald-50 border-emerald-200 text-emerald-700'
            }`}
          >
            <span className="block text-[10px] uppercase font-bold tracking-wider opacity-80">
              Actual Issued
            </span>
            <span className="font-bold">
              {formatQty(selectedNode.actualQty)}{' '}
              {selectedNode.quantityDeltaPercent !== 0 && (
                <span className="text-[10px]">
                  ({selectedNode.quantityDeltaPercent > 0 ? '+' : ''}
                  {selectedNode.quantityDeltaPercent.toFixed(1)}%)
                </span>
              )}
            </span>
          </div>

          <div className="bg-slate-50 p-2 rounded border border-slate-200">
            <span className="text-slate-400 block text-[10px] uppercase font-bold tracking-wider">
              Baseline Cost
            </span>
            <span className="font-semibold text-slate-800">
              {formatCurrency(selectedNode.baselineCost)}
            </span>
          </div>

          <div
            className={`p-2 rounded border ${
              isOverrun
                ? 'bg-rose-50 border-rose-200 text-rose-700'
                : 'bg-emerald-50 border-emerald-200 text-emerald-700'
            }`}
          >
            <span className="block text-[10px] uppercase font-bold tracking-wider opacity-80">
              Net Variance
            </span>
            <span className="font-bold">
              {formatCurrency(selectedNode.costDelta)}
            </span>
          </div>
        </div>

        {/* Hierarchy Context */}
        <div className="bg-slate-50 p-2 rounded border border-slate-200 text-xs mb-2">
          <div className="flex justify-between items-center text-[11px] mb-1 font-mono">
            <span className="text-slate-500">
              Station: <strong className="text-slate-800">{selectedNode.station}</strong>
            </span>
            <span className="text-slate-500">
              Level: <strong className="text-slate-800">Level {selectedNode.level}</strong>
            </span>
          </div>
          <div className="text-slate-600 text-[11px] truncate">
            Material: High-Grade Composite Specification Class-A
          </div>
        </div>

        {/* Deviation Concession Callout */}
        {dossier.deviationConcession && (
          <div className="p-2 bg-amber-50/80 border border-amber-300 rounded text-xs text-slate-800 mb-2.5">
            <div className="flex items-center gap-1 font-bold text-amber-800 uppercase text-[10px] mb-0.5">
              <AlertTriangle className="w-3.5 h-3.5 text-amber-600" />
              Deviation Concession {dossier.deviationConcession.code}:
            </div>
            <p className="text-slate-600 text-[11px] leading-tight">
              {dossier.deviationConcession.note}
            </p>
          </div>
        )}

      </div>

      {/* ================= Lower 50%: MAS Root-Cause Audit Report & AI Assistant ================= */}
      <div className="p-3.5 flex-1 flex flex-col justify-between bg-slate-50/60">
        <div className="flex flex-col gap-2.5">
          <div className="flex items-center justify-between border-b border-slate-200 pb-1.5">
            <span className="text-xs font-bold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-blue-600" />
              AI Root-Cause Audit Report
            </span>
            <span className="font-mono text-[10px] text-emerald-700 bg-emerald-50 border border-emerald-200 px-1.5 py-0.5 rounded font-medium">
              {dossier.confidence}% Conf
            </span>
          </div>

          {/* Root-cause cards */}
          <div className="space-y-1.5">
            {dossier.rootCauses.map((rc) => (
              <div
                key={rc.id}
                className={`p-2 rounded border-l-2 bg-white border border-slate-200 shadow-2xs ${
                  rc.severity === 'error' ? 'border-l-rose-600' : 'border-l-amber-500'
                }`}
              >
                <div className="flex items-center justify-between font-mono text-xs">
                  <span
                    className={`font-bold ${
                      rc.severity === 'error' ? 'text-rose-600' : 'text-slate-800'
                    }`}
                  >
                    {rc.id}: {rc.title}
                  </span>
                  <span className="font-bold text-rose-600">
                    {formatCurrency(rc.amount)}
                  </span>
                </div>
                <p className="text-slate-500 text-[11px] mt-0.5 leading-snug">
                  {rc.description}
                </p>
              </div>
            ))}
          </div>

          {/* Impact Echelon Box */}
          <div className="bg-white border border-slate-200 rounded p-2 text-xs font-mono">
            <div className="text-[10px] uppercase text-slate-500 font-bold mb-1 font-sans flex justify-between">
              <span>Impact Echelon:</span>
              <span className="text-rose-600 font-semibold">{dossier.impactEchelon}</span>
            </div>
            <div className="text-slate-500 flex justify-between">
              <span>Lead Time Slippage:</span>
              <span className="text-slate-800 font-semibold">{dossier.leadTimeSlippage}</span>
            </div>
            <div className="text-slate-500 flex justify-between">
              <span>Affected Assemblies:</span>
              <span className="text-slate-800 font-semibold">
                {dossier.affectedAssembliesCount} nodes in branch
              </span>
            </div>
          </div>

          {/* Prescriptive Actions */}
          <div className="bg-white border border-slate-200 rounded p-2">
            <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider block mb-1">
              Prescriptive Actions:
            </span>
            <ul className="text-[11px] font-mono text-slate-700 space-y-1 list-disc pl-3">
              {dossier.prescriptiveActions.map((action, idx) => (
                <li key={idx}>{action}</li>
              ))}
            </ul>
          </div>
        </div>

        {/* Command Bar Footer */}
        <div className="pt-3 border-t border-slate-200 mt-3">
          <div className="flex flex-wrap gap-1 mb-2">
            <button
              type="button"
              onClick={() => handleQuickCommand(`Break down root-cause attribution for ${selectedNode.id}`)}
              className="text-[10px] font-mono bg-white hover:bg-slate-100 border border-slate-200 px-1.5 py-0.5 rounded text-slate-600 shadow-2xs"
            >
              + Root-Cause Breakdown
            </button>
            <button
              type="button"
              onClick={() => handleQuickCommand(`Locate OP-102 Process Route for ${selectedNode.id}`)}
              className="text-[10px] font-mono bg-white hover:bg-slate-100 border border-slate-200 px-1.5 py-0.5 rounded text-slate-600 shadow-2xs"
            >
              + Locate OP-102
            </button>
            <button
              type="button"
              onClick={() => handleQuickCommand(`Audit ${selectedNode.station.split(' ')[0]} station metrics`)}
              className="text-[10px] font-mono bg-white hover:bg-slate-100 border border-slate-200 px-1.5 py-0.5 rounded text-slate-600 shadow-2xs"
            >
              + Station Audit
            </button>
          </div>

          <div className="relative flex items-center">
            <input
              type="text"
              value={commandInput}
              onChange={(e) => setCommandInput(e.target.value)}
              placeholder="Ask engineering assistant or enter command..."
              className="w-full h-8 pl-2 pr-8 text-xs bg-white border border-slate-200 rounded text-slate-800 placeholder:text-slate-400 focus:outline-hidden focus:border-blue-500 shadow-xs"
            />
            <button
              type="button"
              onClick={() => {
                if (commandInput.trim()) {
                  alert(`AI Command Dispatched: "${commandInput}"`);
                  setCommandInput('');
                }
              }}
              className="absolute right-1 w-6 h-6 bg-blue-600 text-white rounded flex items-center justify-center hover:bg-blue-700 transition-colors shadow-2xs"
            >
              <ArrowUp className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>
    </aside>
  );
};
