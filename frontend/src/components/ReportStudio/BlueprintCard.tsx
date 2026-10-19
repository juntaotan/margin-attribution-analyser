import React from 'react';
import { Calculator, Database, Filter, Layers } from 'lucide-react';
import { SemanticExecutionPlan } from './types';

const SectionTitle: React.FC<{ icon: React.ReactNode; children: React.ReactNode }> = ({ icon, children }) => (
  <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
    {icon}
    <span>{children}</span>
  </div>
);

/**
 * Explains the AI-generated execution blueprint: source table, filters, input fields and formula, so that the
 * user can audit how a placeholder value is computed.
 */
export const BlueprintCard: React.FC<{ plan: SemanticExecutionPlan }> = ({ plan }) => (
  <div className="bg-gradient-to-br from-white to-blue-50/40 rounded-xl border border-blue-200/80 p-4 space-y-3.5 shadow-xs">
    <div className="flex items-center justify-between border-b border-blue-100 pb-2">
      <div className="flex items-center gap-1.5 font-bold text-xs text-blue-950">
        <Calculator className="w-4 h-4 text-blue-600" />
        <span>AI Semantic Intent &amp; Execution Blueprint</span>
      </div>
      <span className="text-[10px] bg-blue-100 text-blue-800 font-mono px-1.5 py-0.5 rounded font-medium">
        Auto-Derived
      </span>
    </div>

    <div className="space-y-1">
      <SectionTitle icon={<Database className="w-3.5 h-3.5 text-blue-600" />}>1. Source Data Table:</SectionTitle>
      <div className="bg-white p-2 rounded-md border border-slate-200 text-xs font-mono text-blue-900 font-semibold">
        {plan.sourceTableLabel}
      </div>
    </div>

    <div className="space-y-1">
      <SectionTitle icon={<Filter className="w-3.5 h-3.5 text-amber-600" />}>2. Filter Criteria:</SectionTitle>
      <ul className="bg-white p-2 rounded-md border border-slate-200 space-y-1 text-[11px] font-mono text-slate-700">
        {plan.filterConditions.map((condition, i) => (
          <li key={i} className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
            <span>{condition}</span>
          </li>
        ))}
      </ul>
    </div>

    <div className="space-y-1">
      <SectionTitle icon={<Layers className="w-3.5 h-3.5 text-indigo-600" />}>3. Input Field Mappings:</SectionTitle>
      <div className="bg-white rounded-md border border-slate-200 divide-y divide-slate-100 text-[11px]">
        {plan.inputFields.map((field, i) => (
          <div key={i} className="p-2 space-y-0.5">
            <div className="flex items-center justify-between font-mono">
              <span className="font-bold text-indigo-700">{field.field}</span>
              <span className="text-slate-500 text-[10px]">{field.label}</span>
            </div>
            <p className="text-[10px] text-slate-400">{field.description}</p>
          </div>
        ))}
      </div>
    </div>

    <div className="space-y-1">
      <SectionTitle icon={<Calculator className="w-3.5 h-3.5 text-emerald-600" />}>
        4. Processing &amp; Computation Formula:
      </SectionTitle>
      <div className="bg-slate-900 text-emerald-300 p-2.5 rounded-md font-mono text-[11px] break-all">
        {plan.formula}
      </div>
      <p className="text-[11px] text-slate-500 pt-0.5 leading-snug">
        <strong>Business Semantic:</strong> {plan.formulaDescription}
      </p>
    </div>

    <div className="p-2.5 bg-blue-50/80 rounded-md border border-blue-200/70 text-[11px] text-blue-900 leading-relaxed">
      <span className="font-bold block mb-0.5">💡 Auditability &amp; Traceability Guarantee:</span>
      {plan.explanation}
    </div>
  </div>
);
