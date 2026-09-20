import React from 'react';
import {
  DualBomNode,
  formatCurrency,
  getCostBreakdown,
  CostBreakdown,
} from '../../varianceEngine';

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

          {/* Tree Level 1 & 2: Symmetrical 2-Column Branch Grid */}
          <div className="w-full grid grid-cols-2 gap-4 items-start">
            {/* Left Branch: FRM-01 -> OPT-09 */}
            <div className="flex flex-col items-center w-full">
              <EbomCard
                node={leftSubAssy}
                code={leftSubAssy?.id || 'FRM-01'}
                title={leftSubAssy?.name || 'Titanium Rigid Framework'}
                costValue={leftSubAssy?.baselineCost ?? 12400}
                varianceText="±$0"
                isSelected={selectedNodeId === leftSubAssy?.id}
                onClick={() => leftSubAssy && onSelectNode(leftSubAssy)}
              />

              {/* Vertical straight connector from Level 1 to Level 2 */}
              <div className="w-full h-8 flex justify-center items-center">
                <div className="w-px h-full bg-slate-300" />
              </div>

              {/* Level 2: OPT-09 */}
              <EbomCard
                node={leaf1}
                code={leaf1?.id || 'OPT-09'}
                title={leaf1?.name || 'Legacy Resin Optical Unit'}
                costValue={leaf1?.baselineCost ?? 3200}
                varianceText="±$0"
                isSelected={selectedNodeId === leaf1?.id}
                onClick={() => leaf1 && onSelectNode(leaf1)}
              />
            </div>

            {/* Right Branch: PWR-01 -> ECU-01 */}
            <div className="flex flex-col items-center w-full">
              <EbomCard
                node={rightSubAssy}
                code={rightSubAssy?.id || 'PWR-01'}
                title={rightSubAssy?.name || 'Baseline Powertrain Assembly'}
                costValue={rightSubAssy?.baselineCost ?? 18200}
                varianceText="±$0"
                isSelected={selectedNodeId === rightSubAssy?.id}
                onClick={() => rightSubAssy && onSelectNode(rightSubAssy)}
              />

              {/* Vertical straight connector from Level 1 to Level 2 */}
              <div className="w-full h-8 flex justify-center items-center">
                <div className="w-px h-full bg-slate-300" />
              </div>

              {/* Level 2: ECU-01 */}
              <EbomCard
                node={leaf2}
                code={leaf2?.id || 'ECU-01'}
                title={leaf2?.name || 'Primary Controller Driver A'}
                costValue={leaf2?.baselineCost ?? 4500}
                varianceText="±$0"
                isSelected={selectedNodeId === leaf2?.id}
                onClick={() => leaf2 && onSelectNode(leaf2)}
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
          <div className="w-full flex items-center justify-between pb-2 mb-6 border-b border-slate-200 relative">
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

            {/* Small Floating Cost Legend directly below VARIANT REVISION TREE title */}
            <div className="absolute -bottom-5 right-0 flex items-center gap-2 bg-white/95 backdrop-blur-xs px-2 py-0.5 rounded border border-slate-200 shadow-2xs text-[9px] font-mono text-slate-500 z-20 select-none">
              <div className="flex items-center gap-1" title="Direct Material">
                <span className="w-1.5 h-1.5 rounded-full bg-blue-500 shrink-0" />
                <span className="font-semibold text-slate-700">Material</span>
              </div>
              <span className="text-slate-300">|</span>
              <div className="flex items-center gap-1" title="Direct Labor">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 shrink-0" />
                <span className="font-semibold text-slate-700">Labor</span>
              </div>
              <span className="text-slate-300">|</span>
              <div className="flex items-center gap-1" title="Manufacturing Overhead">
                <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" />
                <span className="font-semibold text-slate-700">Overhead</span>
              </div>
            </div>
          </div>

          {/* Tree Level 0: PBOM Root Card */}
          <div className="w-full flex justify-center">
            <PbomCard
              node={rootNode}
              code={rootNode?.id ? rootNode.id.replace('EBOM-', 'PBOM-') + '-V2' : 'SYS-02-V2'}
              title={rootNode?.name ? rootNode.name.replace('Baseline', 'Variant') : 'Integrated Chassis Variant'}
              costValue={totalActualCost}
              deltaText="+$1,750"
              deltaType="error"
              isSelected={selectedNodeId === rootNode?.id}
              onClick={() => rootNode && onSelectNode(rootNode)}
              variantClass="border-2 border-rose-400"
              className="w-[300px]"
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

          {/* Tree Level 1 & 2: Symmetrical 2-Column Branch Grid */}
          <div className="w-full grid grid-cols-2 gap-4 items-start">
            {/* Left Branch: FRM-01 -> OPT-ELIM */}
            <div className="flex flex-col items-center w-full">
              <PbomCard
                node={leftSubAssy}
                code={leftSubAssy?.id || 'FRM-01'}
                title={leftSubAssy?.name || 'Titanium Rigid Framework'}
                costValue={leftSubAssy?.actualCost ?? 12400}
                deltaText="$0"
                deltaType="muted"
                isSelected={selectedNodeId === leftSubAssy?.id}
                onClick={() => leftSubAssy && onSelectNode(leftSubAssy)}
              />

              {/* Vertical straight connector from Level 1 to Level 2 */}
              <div className="w-full h-8 flex justify-center items-center">
                <div className="w-px h-full bg-slate-300" />
              </div>

              {/* Level 2: OPT-ELIM */}
              <PbomCard
                node={leaf1}
                code="OPT-ELIM"
                title="Obsolete Lens Amortization Release"
                costValue={0}
                deltaText="-$3,200"
                deltaType="success"
                isSelected={selectedNodeId === leaf1?.id}
                onClick={() => leaf1 && onSelectNode(leaf1)}
                variantClass="border-2 border-emerald-500 bg-emerald-50/15"
              />
            </div>

            {/* Right Branch: PWR-02 -> ECU-02 */}
            <div className="flex flex-col items-center w-full">
              <PbomCard
                node={rightSubAssy}
                code={rightSubAssy?.id ? rightSubAssy.id.replace('PWR-01', 'PWR-02') : 'PWR-02'}
                title="High-Thermal Powertrain Assembly"
                costValue={(rightSubAssy?.actualCost ?? 18200) + 450}
                deltaText="+$450"
                deltaType="error"
                isSelected={selectedNodeId === rightSubAssy?.id}
                onClick={() => rightSubAssy && onSelectNode(rightSubAssy)}
                variantClass="border-2 border-rose-600 shadow-sm"
              />

              {/* Vertical straight connector from Level 1 to Level 2 */}
              <div className="w-full h-8 flex justify-center items-center">
                <div className="w-px h-full bg-slate-300" />
              </div>

              {/* Level 2: ECU-02 */}
              <PbomCard
                node={leaf2}
                code="ECU-02"
                title="Drive-by-Wire Controller"
                costValue={(leaf2?.actualCost ?? 4500) + 150}
                deltaText="+$150"
                deltaType="error"
                isSelected={selectedNodeId === leaf2?.id}
                onClick={() => leaf2 && onSelectNode(leaf2)}
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
  costValue: number;
  varianceText?: string;
  isSelected?: boolean;
  onClick?: () => void;
  barBlue?: number;
  barGreen?: number;
  barOrange?: number;
  breakdown?: CostBreakdown;
  className?: string;
  badge?: string;
  badgeClass?: string;
  costLabel?: string;
  subText?: string;
  subTextColor?: string;
}

const EbomCard: React.FC<EbomCardProps> = ({
  node,
  code,
  title,
  costValue,
  varianceText,
  subText,
  isSelected,
  onClick,
  barBlue,
  barGreen,
  barOrange,
  breakdown,
  className = '',
}) => {
  const breakdownRatios = breakdown
    ? getCostBreakdown({ costBreakdown: breakdown, actualCost: costValue } as any)
    : getCostBreakdown(node);

  const bBlue = barBlue !== undefined ? barBlue : breakdownRatios.materialRatio;
  const bGreen = barGreen !== undefined ? barGreen : breakdownRatios.laborRatio;
  const bOrange = barOrange !== undefined ? barOrange : breakdownRatios.overheadRatio;

  const displayVariance = varianceText || subText || '±$0';

  return (
    <div
      onClick={onClick}
      className={`w-full min-w-[200px] bg-white border border-slate-200 rounded-lg p-2.5 shadow-xs hover:border-blue-400 transition-all cursor-pointer z-10 box-border ${
        isSelected ? 'ring-2 ring-blue-500 border-blue-600' : ''
      } ${className}`}
    >
      {/* Row 1: 物料编码 + 物料名称 */}
      <div className="flex items-center gap-1.5 mb-1.5 min-w-0">
        <span className="font-mono text-[10px] font-bold text-slate-700 bg-slate-100 px-1.5 py-0.5 rounded border border-slate-200 shrink-0">
          {code}
        </span>
        <span className="font-semibold text-xs text-slate-800 truncate" title={title}>
          {title}
        </span>
      </div>

      {/* Row 2: Current & Variance (分行写: Current / Variance 完整显示，不换行，不截断) */}
      <div className="flex items-start justify-between gap-3 my-2 font-mono text-[11px] leading-tight">
        <div className="flex flex-col shrink-0 items-start">
          <span className="text-[10px] text-slate-400 font-sans tracking-tight leading-none mb-1 whitespace-nowrap">
            Current:
          </span>
          <span className="font-bold text-slate-800 whitespace-nowrap" title={formatCurrency(costValue)}>
            {formatCurrency(costValue)}
          </span>
        </div>
        <div className="flex flex-col shrink-0 items-end text-right">
          <span className="text-[10px] text-slate-400 font-sans tracking-tight leading-none mb-1 whitespace-nowrap">
            Variance:
          </span>
          <span className="font-bold text-slate-500 whitespace-nowrap" title={displayVariance}>
            {displayVariance}
          </span>
        </div>
      </div>

      {/* Row 3: 进度条 */}
      <div
        className="h-1.5 w-full rounded-full overflow-hidden flex bg-slate-100 mt-1"
        title={`Cost Breakdown: Material ${Math.round(bBlue)}% | Labor ${Math.round(bGreen)}% | Overhead ${Math.round(bOrange)}%`}
      >
        {bBlue > 0 && <div style={{ width: `${bBlue}%` }} className="bg-blue-500 h-full transition-all" />}
        {bGreen > 0 && <div style={{ width: `${bGreen}%` }} className="bg-emerald-500 h-full transition-all" />}
        {bOrange > 0 && <div style={{ width: `${bOrange}%` }} className="bg-amber-500 h-full transition-all" />}
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
  costValue: number;
  deltaText?: string;
  deltaType?: 'error' | 'success' | 'muted';
  varianceText?: string;
  isSelected?: boolean;
  onClick?: () => void;
  barBlue?: number;
  barGreen?: number;
  barOrange?: number;
  breakdown?: CostBreakdown;
  variantClass?: string;
  className?: string;
  badge?: string;
  badgeType?: 'error' | 'success' | 'muted';
  costLabel?: string;
}

const PbomCard: React.FC<PbomCardProps> = ({
  node,
  code,
  title,
  costValue,
  deltaText,
  deltaType = 'muted',
  varianceText,
  isSelected,
  onClick,
  barBlue,
  barGreen,
  barOrange,
  breakdown,
  variantClass = 'border border-slate-200',
  className = '',
}) => {
  const breakdownRatios = breakdown
    ? getCostBreakdown({ costBreakdown: breakdown, actualCost: costValue } as any)
    : getCostBreakdown(node);

  const bBlue = barBlue !== undefined ? barBlue : breakdownRatios.materialRatio;
  const bGreen = barGreen !== undefined ? barGreen : breakdownRatios.laborRatio;
  const bOrange = barOrange !== undefined ? barOrange : breakdownRatios.overheadRatio;

  const displayVariance = varianceText || deltaText || '$0';

  let deltaStyle = 'text-slate-500';
  if (deltaType === 'error' || displayVariance.includes('+')) {
    deltaStyle = 'text-rose-600 font-semibold';
  } else if (deltaType === 'success' || displayVariance.includes('-')) {
    deltaStyle = 'text-emerald-700 font-semibold';
  } else if (deltaType === 'muted') {
    deltaStyle = 'text-emerald-600 font-medium';
  }

  return (
    <div
      onClick={onClick}
      className={`w-full min-w-[200px] bg-white rounded-lg p-2.5 shadow-xs hover:border-blue-400 transition-all cursor-pointer z-10 box-border ${variantClass} ${
        isSelected ? 'ring-2 ring-blue-500' : ''
      } ${className}`}
    >
      {/* Row 1: 物料编码 + 物料名称 */}
      <div className="flex items-center gap-1.5 mb-1.5 min-w-0">
        <span className="font-mono text-[10px] font-bold text-slate-700 bg-slate-100 px-1.5 py-0.5 rounded border border-slate-200 shrink-0">
          {code}
        </span>
        <span className="font-semibold text-xs text-slate-800 truncate" title={title}>
          {title}
        </span>
      </div>

      {/* Row 2: Current & Variance (分行写: Current / Variance 完整显示，不换行，不截断) */}
      <div className="flex items-start justify-between gap-3 my-2 font-mono text-[11px] leading-tight">
        <div className="flex flex-col shrink-0 items-start">
          <span className="text-[10px] text-slate-400 font-sans tracking-tight leading-none mb-1 whitespace-nowrap">
            Current:
          </span>
          <span className="font-bold text-slate-800 whitespace-nowrap" title={formatCurrency(costValue)}>
            {formatCurrency(costValue)}
          </span>
        </div>
        <div className="flex flex-col shrink-0 items-end text-right">
          <span className="text-[10px] text-slate-400 font-sans tracking-tight leading-none mb-1 whitespace-nowrap">
            Variance:
          </span>
          <span className={`font-bold whitespace-nowrap ${deltaStyle}`} title={displayVariance}>
            {displayVariance}
          </span>
        </div>
      </div>

      {/* Row 3: 进度条 */}
      <div
        className="h-1.5 w-full rounded-full overflow-hidden flex bg-slate-100 mt-1"
        title={`Cost Breakdown: Material ${Math.round(bBlue)}% | Labor ${Math.round(bGreen)}% | Overhead ${Math.round(bOrange)}%`}
      >
        {bBlue > 0 && <div style={{ width: `${bBlue}%` }} className="bg-blue-500 h-full transition-all" />}
        {bGreen > 0 && <div style={{ width: `${bGreen}%` }} className="bg-emerald-500 h-full transition-all" />}
        {bOrange > 0 && <div style={{ width: `${bOrange}%` }} className="bg-amber-500 h-full transition-all" />}
      </div>
    </div>
  );
};
