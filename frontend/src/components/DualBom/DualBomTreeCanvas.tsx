import React from 'react';
import { DualBomNode, formatCurrency } from '../../varianceEngine';

interface DualBomTreeCanvasProps {
  nodes: DualBomNode[];
  selectedNodeId: string | null;
  onSelectNode: (node: DualBomNode) => void;
}

export const DualBomTreeCanvas: React.FC<DualBomTreeCanvasProps> = ({
  nodes,
  selectedNodeId,
  onSelectNode,
}) => {
  // Find key nodes in hierarchy
  const rootNode = nodes.find((n) => n.level === 0) || nodes[0];
  const level1Nodes = nodes.filter((n) => n.level === 1);
  const leftSubAssy = level1Nodes[0] || rootNode;
  const rightSubAssy = level1Nodes[1] || level1Nodes[0] || rootNode;

  // Level 2 nodes under rightSubAssy
  const level2Nodes = nodes.filter((n) => n.level === 2);
  const leaf1 = level2Nodes[0] || level1Nodes[2] || rootNode;
  const leaf2 = level2Nodes[1] || level1Nodes[3] || rootNode;

  const totalBaselineCost = rootNode?.baselineCost ?? 45000;
  const totalActualCost = rootNode?.actualCost ?? 46750;
  const netRootDelta = totalActualCost - totalBaselineCost;

  return (
    <section className="w-full border-b border-slate-200 bg-[#f8fafd] py-5 px-6 overflow-x-auto select-none min-w-[980px]">
      {/* 2-Column Dual Tree Container with Central MAS Correlation Pillar */}
      <div className="max-w-[1180px] mx-auto grid grid-cols-[1fr,56px,1fr] gap-4 relative items-start">
        {/* ========================================================================= */}
        {/* LEFT COLUMN: BASELINE TREE (EBOM-R1)                                      */}
        {/* ========================================================================= */}
        <div className="flex flex-col items-center">
          {/* Header Bar */}
          <div className="w-full flex items-center justify-between pb-2 mb-6 border-b border-slate-200">
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-slate-400" />
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 font-mono">
                BASELINE TREE (EBOM-R1)
              </h2>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded border border-slate-300 text-slate-500 bg-white">
                EBOM-R1
              </span>
            </div>
            <div className="text-xs font-mono text-slate-600">
              Total Target: <strong className="text-slate-900">{formatCurrency(totalBaselineCost)}</strong>
            </div>
          </div>

          {/* Tree Level 0: Root Card */}
          <div className="w-full flex justify-center">
            <EbomCard
              node={rootNode}
              code={rootNode?.id || 'SYS-00'}
              title={rootNode?.name || 'Integrated Chassis Baseline'}
              badge="Baseline Root"
              costLabel="Total"
              costValue={totalBaselineCost}
              subText="Standard Architecture"
              isSelected={selectedNodeId === rootNode?.id}
              onClick={() => rootNode && onSelectNode(rootNode)}
              barBlue={55}
              barGreen={25}
              barOrange={20}
              className="w-[340px]"
            />
          </div>

          {/* SVG Connector: Root to Level 1 */}
          <div className="w-full h-16 relative">
            <svg
              className="w-full h-full overflow-visible pointer-events-none"
              viewBox="0 0 100 40"
              preserveAspectRatio="none"
            >
              {/* Vertical stem from root */}
              <line x1="50" y1="0" x2="50" y2="12" stroke="#cbd5e1" strokeWidth="1.5" />
              {/* Branch curve to Left child (at 25%) */}
              <path
                d="M 50 12 C 50 26, 25 26, 25 40"
                stroke="#cbd5e1"
                strokeWidth="1.5"
                fill="none"
                vectorEffect="non-scaling-stroke"
              />
              {/* Branch curve to Right child (at 75%) */}
              <path
                d="M 50 12 C 50 26, 75 26, 75 40"
                stroke="#cbd5e1"
                strokeWidth="1.5"
                fill="none"
                vectorEffect="non-scaling-stroke"
              />
            </svg>
          </div>

          {/* Tree Level 1: Sub-assemblies */}
          <div className="w-full grid grid-cols-2 gap-4">
            {/* Left Sub-assembly (e.g. FRM-01) */}
            <div className="flex flex-col items-center">
              <EbomCard
                node={leftSubAssy}
                code={leftSubAssy?.id || 'FRM-01'}
                title={leftSubAssy?.name || 'Titanium Rigid Framework'}
                badge="Unchanged"
                costLabel="Target"
                costValue={leftSubAssy?.baselineCost ?? 12400}
                subText="± $0"
                isSelected={selectedNodeId === leftSubAssy?.id}
                onClick={() => leftSubAssy && onSelectNode(leftSubAssy)}
                barBlue={60}
                barGreen={25}
                barOrange={15}
              />
            </div>

            {/* Right Sub-assembly (e.g. PWR-01) */}
            <div className="flex flex-col items-center">
              <EbomCard
                node={rightSubAssy}
                code={rightSubAssy?.id || 'PWR-01'}
                title={rightSubAssy?.name || 'Baseline Powertrain Assembly'}
                badge="Nominal Env"
                costLabel="Target"
                costValue={rightSubAssy?.baselineCost ?? 18200}
                subText="Baseline Datum"
                subTextColor="text-blue-600"
                isSelected={selectedNodeId === rightSubAssy?.id}
                onClick={() => rightSubAssy && onSelectNode(rightSubAssy)}
                barBlue={50}
                barGreen={30}
                barOrange={20}
              />

              {/* SVG Connector: Right Sub-assembly to Level 2 Children */}
              <div className="w-full h-14 relative">
                <svg
                  className="w-full h-full overflow-visible pointer-events-none"
                  viewBox="0 0 100 40"
                  preserveAspectRatio="none"
                >
                  <line x1="50" y1="0" x2="50" y2="12" stroke="#cbd5e1" strokeWidth="1.5" />
                  <path
                    d="M 50 12 C 50 26, 25 26, 25 40"
                    stroke="#cbd5e1"
                    strokeWidth="1.5"
                    fill="none"
                    vectorEffect="non-scaling-stroke"
                  />
                  <path
                    d="M 50 12 C 50 26, 75 26, 75 40"
                    stroke="#cbd5e1"
                    strokeWidth="1.5"
                    fill="none"
                    vectorEffect="non-scaling-stroke"
                  />
                </svg>
              </div>
            </div>
          </div>

          {/* Tree Level 2: Grandchildren (under PWR-01 on the right half) */}
          <div className="w-full grid grid-cols-2 gap-4">
            {/* Spacer for left column */}
            <div />

            {/* Level 2 Cards row under right sub-assembly */}
            <div className="grid grid-cols-2 gap-2 w-full">
              {/* Leaf 1: OPT-09 */}
              <EbomCard
                node={leaf1}
                code={leaf1?.id || 'OPT-09'}
                title={leaf1?.name || 'Legacy Resin Optical Unit'}
                badge="Phased Out"
                badgeClass="bg-amber-50 text-amber-700 border border-amber-200"
                costLabel="Target"
                costValue={leaf1?.baselineCost ?? 3200}
                subText="Release Candidate"
                subTextColor="text-amber-600"
                isSelected={selectedNodeId === leaf1?.id}
                onClick={() => leaf1 && onSelectNode(leaf1)}
                barBlue={45}
                barGreen={20}
                barOrange={35}
              />

              {/* Leaf 2: ECU-01 */}
              <EbomCard
                node={leaf2}
                code={leaf2?.id || 'ECU-01'}
                title={leaf2?.name || 'Primary Controller Driver A'}
                badge="Standard Component"
                costLabel="Target"
                costValue={leaf2?.baselineCost ?? 4500}
                subText="Standard Baseline"
                isSelected={selectedNodeId === leaf2?.id}
                onClick={() => leaf2 && onSelectNode(leaf2)}
                barBlue={50}
                barGreen={30}
                barOrange={20}
              />
            </div>
          </div>
        </div>

        {/* ========================================================================= */}
        {/* CENTER COLUMN: MAS Causal Correlation Core Pillar                         */}
        {/* ========================================================================= */}
        <div className="flex flex-col items-center justify-center h-full min-h-[440px] relative py-8">
          {/* Vertical dashed line */}
          <div className="absolute top-10 bottom-10 w-px border-l-2 border-dashed border-slate-300 pointer-events-none" />

          {/* Center Vertical Pill Badge */}
          <div className="z-10 bg-white border border-slate-300 rounded-lg p-2 shadow-xs flex flex-col items-center justify-center font-mono text-[9px] font-bold text-slate-600 uppercase tracking-widest gap-2">
            <span style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}>
              MAS Causal
            </span>
            <span style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}>
              Correlation
            </span>
            <span style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}>
              Core
            </span>
          </div>
        </div>

        {/* ========================================================================= */}
        {/* RIGHT COLUMN: VARIANT REVISION TREE (PBOM-V2)                             */}
        {/* ========================================================================= */}
        <div className="flex flex-col items-center">
          {/* Header Bar */}
          <div className="w-full flex items-center justify-between pb-2 mb-6 border-b border-slate-200">
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-blue-500" />
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 font-mono">
                VARIANT REVISION TREE (PBOM-V2)
              </h2>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded border border-blue-200 text-blue-700 bg-blue-50 font-semibold">
                PBOM-V2
              </span>
            </div>
            <div className="text-xs font-mono text-slate-600">
              Current: <strong className="text-slate-900">{formatCurrency(totalActualCost)}</strong>{' '}
              <span className="text-rose-600 font-bold">({formatCurrency(netRootDelta)})</span>
            </div>
          </div>

          {/* Tree Level 0: PBOM Root Card */}
          <div className="w-full flex justify-center">
            <PbomCard
              node={rootNode}
              code={rootNode?.id ? rootNode.id.replace('EBOM-', 'PBOM-') + '-V2' : 'SYS-02-V2'}
              title={rootNode?.name ? rootNode.name.replace('Baseline', 'Variant') : 'Integrated Chassis Variant'}
              badge="+ $1,750 (Overrun)"
              badgeType="error"
              costLabel="Current"
              costValue={totalActualCost}
              deltaText="Δ +$1,750 (+3.9%)"
              deltaType="error"
              isSelected={selectedNodeId === rootNode?.id}
              onClick={() => rootNode && onSelectNode(rootNode)}
              barBlue={55}
              barGreen={25}
              barOrange={20}
              variantClass="border-2 border-rose-400"
              className="w-[340px]"
            />
          </div>

          {/* SVG Connector: PBOM Root to Level 1 */}
          <div className="w-full h-16 relative">
            <svg
              className="w-full h-full overflow-visible pointer-events-none"
              viewBox="0 0 100 40"
              preserveAspectRatio="none"
            >
              <line x1="50" y1="0" x2="50" y2="12" stroke="#cbd5e1" strokeWidth="1.5" />
              <path
                d="M 50 12 C 50 26, 25 26, 25 40"
                stroke="#cbd5e1"
                strokeWidth="1.5"
                fill="none"
                vectorEffect="non-scaling-stroke"
              />
              <path
                d="M 50 12 C 50 26, 75 26, 75 40"
                stroke="#cbd5e1"
                strokeWidth="1.5"
                fill="none"
                vectorEffect="non-scaling-stroke"
              />
            </svg>
          </div>

          {/* Tree Level 1: Sub-assemblies */}
          <div className="w-full grid grid-cols-2 gap-4">
            {/* Left PBOM Sub-assembly (Identical / Muted) */}
            <div className="flex flex-col items-center">
              <PbomCard
                node={leftSubAssy}
                code={leftSubAssy?.id || 'FRM-01'}
                title={leftSubAssy?.name || 'Titanium Rigid Framework'}
                badge="Identical (Muted)"
                badgeType="muted"
                costLabel="Current"
                costValue={leftSubAssy?.actualCost ?? 12400}
                deltaText="Δ $0 (Direct Match)"
                deltaType="muted"
                isSelected={selectedNodeId === leftSubAssy?.id}
                onClick={() => leftSubAssy && onSelectNode(leftSubAssy)}
                barBlue={60}
                barGreen={25}
                barOrange={15}
              />
            </div>

            {/* Right PBOM Sub-assembly (PWR-02 Major Overrun) */}
            <div className="flex flex-col items-center">
              <PbomCard
                node={rightSubAssy}
                code={rightSubAssy?.id ? rightSubAssy.id.replace('PWR-01', 'PWR-02') : 'PWR-02'}
                title="High-Thermal Powertrain Assembly"
                badge="+ $450 (Overrun)"
                badgeType="error"
                costLabel="Current"
                costValue={(rightSubAssy?.actualCost ?? 18200) + 450}
                deltaText="Δ +$450 (+2.5%)"
                deltaType="error"
                isSelected={selectedNodeId === rightSubAssy?.id}
                onClick={() => rightSubAssy && onSelectNode(rightSubAssy)}
                barBlue={50}
                barGreen={30}
                barOrange={20}
                variantClass="border-2 border-rose-600 shadow-sm"
              />

              {/* SVG Connector: Right Sub-assembly to Level 2 Children */}
              <div className="w-full h-14 relative">
                <svg
                  className="w-full h-full overflow-visible pointer-events-none"
                  viewBox="0 0 100 40"
                  preserveAspectRatio="none"
                >
                  <line x1="50" y1="0" x2="50" y2="12" stroke="#cbd5e1" strokeWidth="1.5" />
                  <path
                    d="M 50 12 C 50 26, 25 26, 25 40"
                    stroke="#cbd5e1"
                    strokeWidth="1.5"
                    fill="none"
                    vectorEffect="non-scaling-stroke"
                  />
                  <path
                    d="M 50 12 C 50 26, 75 26, 75 40"
                    stroke="#cbd5e1"
                    strokeWidth="1.5"
                    fill="none"
                    vectorEffect="non-scaling-stroke"
                  />
                </svg>
              </div>
            </div>
          </div>

          {/* Tree Level 2: Grandchildren */}
          <div className="w-full grid grid-cols-2 gap-4">
            {/* Spacer for left column */}
            <div />

            {/* Level 2 PBOM Cards */}
            <div className="grid grid-cols-2 gap-2 w-full">
              {/* Leaf 1: OPT-ELIM (Released / Favorable) */}
              <PbomCard
                node={leaf1}
                code="OPT-ELIM"
                title="Obsolete Lens Amortization Relea..."
                badge="-$3,200 // Released"
                badgeType="success"
                costLabel="Net"
                costValue={0}
                deltaText="-$3,200.0"
                deltaType="success"
                isSelected={selectedNodeId === leaf1?.id}
                onClick={() => leaf1 && onSelectNode(leaf1)}
                barBlue={0}
                barGreen={100}
                barOrange={0}
                variantClass="border-2 border-emerald-500 bg-emerald-50/15"
              />

              {/* Leaf 2: ECU-02 (Drive-by-Wire) */}
              <PbomCard
                node={leaf2}
                code="ECU-02"
                title="Drive-by-Wire Controller..."
                badge="+ $150.0"
                badgeType="error"
                costLabel="Current"
                costValue={(leaf2?.actualCost ?? 4500) + 150}
                deltaText="Δ +$150 (+3.3%)"
                deltaType="error"
                isSelected={selectedNodeId === leaf2?.id}
                onClick={() => leaf2 && onSelectNode(leaf2)}
                barBlue={50}
                barGreen={30}
                barOrange={20}
                variantClass="border-2 border-teal-500 bg-teal-50/10"
              />
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};

// =========================================================================
// EBOM Card Component
// =========================================================================
interface EbomCardProps {
  node?: DualBomNode;
  code: string;
  title: string;
  badge: string;
  badgeClass?: string;
  costLabel: string;
  costValue: number;
  subText: string;
  subTextColor?: string;
  isSelected?: boolean;
  onClick?: () => void;
  barBlue: number;
  barGreen: number;
  barOrange: number;
  className?: string;
}

const EbomCard: React.FC<EbomCardProps> = ({
  code,
  title,
  badge,
  badgeClass,
  costLabel,
  costValue,
  subText,
  subTextColor = 'text-slate-500',
  isSelected,
  onClick,
  barBlue,
  barGreen,
  barOrange,
  className = '',
}) => {
  return (
    <div
      onClick={onClick}
      className={`w-full bg-white border border-slate-200 rounded-lg p-3 shadow-xs hover:border-blue-400 transition-all cursor-pointer z-10 ${
        isSelected ? 'ring-2 ring-blue-500 border-blue-600' : ''
      } ${className}`}
    >
      {/* Top Header Row */}
      <div className="flex items-center justify-between gap-1 mb-1">
        <div className="flex items-center gap-1.5 truncate">
          <span className="font-mono text-[10px] font-bold text-slate-700 bg-slate-100 px-1.5 py-0.2 rounded border border-slate-200">
            {code}
          </span>
          <span className="font-bold text-xs text-slate-800 truncate" title={title}>
            {title}
          </span>
        </div>
        <span
          className={`text-[9px] font-mono px-1.5 py-0.5 rounded shrink-0 ${
            badgeClass || 'bg-slate-100 text-slate-500 border border-slate-200'
          }`}
        >
          {badge}
        </span>
      </div>

      {/* Middle Values */}
      <div className="flex items-center justify-between font-mono text-[11px] text-slate-600 my-1.5">
        <span>
          {costLabel}: <strong className="text-slate-800">{formatCurrency(costValue)}</strong>
        </span>
        <span className={`text-[10px] font-medium ${subTextColor}`}>{subText}</span>
      </div>

      {/* Segmented Color Bar */}
      <div className="h-1.5 w-full rounded-full overflow-hidden flex bg-slate-100 mt-1">
        <div style={{ width: `${barBlue}%` }} className="bg-blue-500 h-full" />
        <div style={{ width: `${barGreen}%` }} className="bg-emerald-500 h-full" />
        <div style={{ width: `${barOrange}%` }} className="bg-amber-500 h-full" />
      </div>
    </div>
  );
};

// =========================================================================
// PBOM Card Component
// =========================================================================
interface PbomCardProps {
  node?: DualBomNode;
  code: string;
  title: string;
  badge: string;
  badgeType: 'error' | 'success' | 'muted';
  costLabel: string;
  costValue: number;
  deltaText: string;
  deltaType: 'error' | 'success' | 'muted';
  isSelected?: boolean;
  onClick?: () => void;
  barBlue: number;
  barGreen: number;
  barOrange: number;
  variantClass?: string;
  className?: string;
}

const PbomCard: React.FC<PbomCardProps> = ({
  code,
  title,
  badge,
  badgeType,
  costLabel,
  costValue,
  deltaText,
  deltaType,
  isSelected,
  onClick,
  barBlue,
  barGreen,
  barOrange,
  variantClass = 'border border-slate-200',
  className = '',
}) => {
  let badgeStyle = 'bg-slate-100 text-slate-500 border border-slate-200';
  let deltaStyle = 'text-slate-500';

  if (badgeType === 'error') {
    badgeStyle = 'bg-rose-50 text-rose-600 border border-rose-200 font-bold';
  } else if (badgeType === 'success') {
    badgeStyle = 'bg-emerald-50 text-emerald-700 border border-emerald-300 font-bold';
  } else if (badgeType === 'muted') {
    badgeStyle = 'bg-slate-100 text-slate-400 border border-slate-200';
  }

  if (deltaType === 'error') {
    deltaStyle = 'text-rose-600 font-bold';
  } else if (deltaType === 'success') {
    deltaStyle = 'text-emerald-700 font-bold';
  } else if (deltaType === 'muted') {
    deltaStyle = 'text-emerald-600 font-medium';
  }

  return (
    <div
      onClick={onClick}
      className={`w-full bg-white rounded-lg p-3 shadow-xs hover:border-blue-400 transition-all cursor-pointer z-10 ${variantClass} ${
        isSelected ? 'ring-2 ring-blue-500' : ''
      } ${className}`}
    >
      {/* Top Header Row */}
      <div className="flex items-center justify-between gap-1 mb-1">
        <div className="flex items-center gap-1.5 truncate">
          <span className="font-mono text-[10px] font-bold text-slate-700 bg-slate-100 px-1.5 py-0.2 rounded border border-slate-200">
            {code}
          </span>
          <span className="font-bold text-xs text-slate-800 truncate" title={title}>
            {title}
          </span>
        </div>
        <span className={`text-[9px] font-mono px-1.5 py-0.5 rounded shrink-0 ${badgeStyle}`}>
          {badge}
        </span>
      </div>

      {/* Middle Values */}
      <div className="flex items-center justify-between font-mono text-[11px] text-slate-600 my-1.5">
        <span>
          {costLabel}: <strong className="text-slate-800">{formatCurrency(costValue)}</strong>
        </span>
        <span className={`text-[10px] ${deltaStyle}`}>{deltaText}</span>
      </div>

      {/* Segmented Color Bar */}
      <div className="h-1.5 w-full rounded-full overflow-hidden flex bg-slate-100 mt-1">
        <div style={{ width: `${barBlue}%` }} className="bg-blue-500 h-full" />
        <div style={{ width: `${barGreen}%` }} className="bg-emerald-500 h-full" />
        <div style={{ width: `${barOrange}%` }} className="bg-amber-500 h-full" />
      </div>
    </div>
  );
};
