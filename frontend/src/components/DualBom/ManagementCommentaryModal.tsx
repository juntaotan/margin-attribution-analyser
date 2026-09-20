import React, { useState } from 'react';
import {
  X,
  FileDown,
  FileText,
  TrendingUp,
  Layers,
  Sparkles,
  CheckCircle2,
  Building2,
  Users,
  Globe,
  Loader2,
} from 'lucide-react';
import {
  Document,
  Packer,
  Paragraph,
  TextRun,
  HeadingLevel,
  AlignmentType,
} from 'docx';
import { DualBomNode, formatCurrency, formatQty } from '../../varianceEngine';

interface RootCauseReport {
  inventoryId: string;
  category: string;
  categoryLabel: string;
  certainty: string;
  evidence: string[];
  summary: string | null;
  aiGenerated: boolean;
  aiMessage: string | null;
}

export interface ManagementCommentaryModalProps {
  isOpen: boolean;
  onClose: () => void;
  actualStartDate: string;
  actualEndDate: string;
  comparableStartDate: string;
  comparableEndDate: string;
  selectedNode?: DualBomNode | null;
  report?: RootCauseReport | null;
}

export const ManagementCommentaryModal: React.FC<ManagementCommentaryModalProps> = ({
  isOpen,
  onClose,
  actualStartDate,
  actualEndDate,
  comparableStartDate,
  comparableEndDate,
  selectedNode,
  report,
}) => {
  const [isExporting, setIsExporting] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);

  React.useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const handleExportDocx = async () => {
    try {
      setIsExporting(true);

      const doc = new Document({
        sections: [
          {
            properties: {
              page: {
                margin: {
                  top: 1440, // 1 inch
                  right: 1440,
                  bottom: 1440,
                  left: 1440,
                },
              },
            },
            children: [
              // Main Title
              new Paragraph({
                text: 'Management Commentary: Gross Margin Performance',
                heading: HeadingLevel.TITLE,
                alignment: AlignmentType.CENTER,
                spacing: { after: 200 },
              }),

              // Subtitle / Metadata
              new Paragraph({
                alignment: AlignmentType.CENTER,
                spacing: { after: 400 },
                children: [
                  new TextRun({
                    text: `Reporting Period: Actual (${actualStartDate} to ${actualEndDate}) vs. Comparable (${comparableStartDate} to ${comparableEndDate})`,
                    italics: true,
                    color: '555555',
                    size: 20, // 10pt
                  }),
                ],
              }),

              // Section 1
              new Paragraph({
                text: '1. Performance Overview',
                heading: HeadingLevel.HEADING_1,
                spacing: { before: 300, after: 150 },
              }),
              new Paragraph({
                spacing: { after: 120 },
                children: [
                  new TextRun({
                    text: 'This executive overview outlines key gross margin variance dynamics and cost drivers observed between the evaluated operational period and the comparable benchmark baseline. Through the Dual-BOM multi-attribute reconciliation engine, manufacturing and inventory movements have been analyzed to decouple structural variances from operational execution deviations.',
                  }),
                ],
              }),
              ...(selectedNode
                ? [
                    new Paragraph({
                      spacing: { after: 120 },
                      children: [
                        new TextRun({
                          text: `Target Component Inspected: ${selectedNode.name} (${selectedNode.id}) | Station: ${selectedNode.station} | ECN: ${selectedNode.ecn}. `,
                          bold: true,
                        }),
                        new TextRun({
                          text: `Standard quantity benchmark was ${formatQty(selectedNode.standardQty)}, whereas actual issued quantity reached ${formatQty(selectedNode.actualQty)} (${selectedNode.quantityDeltaPercent > 0 ? '+' : ''}${selectedNode.quantityDeltaPercent.toFixed(1)}%). Baseline cost was ${formatCurrency(selectedNode.baselineCost)}, culminating in a net variance of ${formatCurrency(selectedNode.costDelta)}.`,
                        }),
                      ],
                    }),
                  ]
                : []),
              ...(report?.summary
                ? [
                    new Paragraph({
                      spacing: { after: 120 },
                      children: [
                        new TextRun({
                          text: `AI Root-Cause Synthesis (${report.categoryLabel} - ${report.certainty}): `,
                          bold: true,
                          color: '1E40AF',
                        }),
                        new TextRun({
                          text: report.summary,
                        }),
                      ],
                    }),
                  ]
                : []),

              // Section 2
              new Paragraph({
                text: '2. Margin Walk / Bridge Analysis',
                heading: HeadingLevel.HEADING_1,
                spacing: { before: 350, after: 150 },
              }),
              new Paragraph({
                spacing: { after: 150 },
                children: [
                  new TextRun({
                    text: 'Section Coverage: Volume Impact \\ Selling Price \\ Sales Mix \\ Purchase Price Variance (PPV) \\ Manufacturing Efficiency & Yield \\ Overhead Under-Absorption',
                    bold: true,
                    color: '2563EB',
                  }),
                ],
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'The Margin Walk decomposes gross margin delta across six core attribution pillars:',
                  }),
                ],
              }),

              // 2.1 Volume Impact
              new Paragraph({
                text: '2.1 Volume Impact',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Evaluates fixed-cost leverage and scale economics resulting from shifts in total production volume against planned throughput expectations.',
                  }),
                ],
              }),

              // 2.2 Selling Price
              new Paragraph({
                text: '2.2 Selling Price',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Reflects the direct impact of Average Selling Price (ASP) adjustments, contract renewals, customer discount structures, and list price realization on margin percentage.',
                  }),
                ],
              }),

              // 2.3 Sales Mix
              new Paragraph({
                text: '2.3 Sales Mix',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Isolates the portfolio margin variance driven by changes in the proportion of higher-margin versus lower-margin product assemblies shipped.',
                  }),
                ],
              }),

              // 2.4 Purchase Price Variance (PPV)
              new Paragraph({
                text: '2.4 Purchase Price Variance (PPV)',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Captures the deviation between actual procurement costs for raw materials/components and standard planned purchase prices, tracking vendor adjustments and supply market fluctuations.',
                  }),
                ],
              }),

              // 2.5 Manufacturing Efficiency & Yield
              new Paragraph({
                text: '2.5 Manufacturing Efficiency & Yield',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Assesses shop-floor execution performance, including thermal scrap rates, machining rework cycles, cycle-time deviations, and direct labor usage anomalies.',
                  }),
                ],
              }),

              // 2.6 Overhead Under-Absorption
              new Paragraph({
                text: '2.6 Overhead Under-Absorption',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 150 },
                children: [
                  new TextRun({
                    text: 'Monitors the over- or under-absorption of plant overhead, machine depreciation, and facility fixed costs driven by equipment utilization rates and machine downtime.',
                  }),
                ],
              }),

              // Section 3
              new Paragraph({
                text: '3. Dimensional Deep-Dives',
                heading: HeadingLevel.HEADING_1,
                spacing: { before: 350, after: 150 },
              }),
              new Paragraph({
                spacing: { after: 150 },
                children: [
                  new TextRun({
                    text: 'Section Coverage: By Product Category / Product Family, By Customer / Channel, By Geographic Region / Market',
                    bold: true,
                    color: '2563EB',
                  }),
                ],
              }),

              // 3.1 By Product Category / Product Family
              new Paragraph({
                text: '3.1 By Product Category / Product Family',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Slices margin variance across discrete product families and sub-assembly clusters to isolate systemic BOM component drift within specific model architectures.',
                  }),
                ],
              }),

              // 3.2 By Customer / Channel
              new Paragraph({
                text: '3.2 By Customer / Channel',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 100 },
                children: [
                  new TextRun({
                    text: 'Attributes margin spreads across tier-1 strategic accounts, OEM partners, and regional direct vs. distributor channels, factoring in customer-specific concessions.',
                  }),
                ],
              }),

              // 3.3 By Geographic Region / Market
              new Paragraph({
                text: '3.3 By Geographic Region / Market',
                heading: HeadingLevel.HEADING_2,
                spacing: { before: 150, after: 80 },
              }),
              new Paragraph({
                spacing: { after: 150 },
                children: [
                  new TextRun({
                    text: 'Breaks down geographical performance, analyzing regional fulfillment logistics, regional tariff exposure, currency translation effects, and local assembly plant performance.',
                  }),
                ],
              }),
            ],
          },
        ],
      });

      const blob = await Packer.toBlob(doc);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `Management_Commentary_Gross_Margin_${actualStartDate}_to_${actualEndDate}.docx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);

      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 3000);
    } catch (err) {
      console.error('Failed to export DOCX:', err);
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-slate-900/60 backdrop-blur-xs animate-in fade-in duration-150"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="bg-white rounded-xl shadow-2xl border border-slate-200 flex flex-col w-full max-w-4xl max-h-[92vh] overflow-hidden">
        {/* Modal Top Bar */}
        <div className="px-6 py-3.5 bg-slate-50 border-b border-slate-200 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center">
              <FileText className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-800 leading-none">
                Management Commentary Report
              </h3>
              <p className="text-[11px] text-slate-500 font-mono mt-0.5">
                Dual-BOM MAS Attribution Framework
              </p>
            </div>
          </div>

          {/* Right Action Icons: Export to docx + Close */}
          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={handleExportDocx}
              disabled={isExporting}
              title="Export report to Microsoft Word (.docx)"
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 active:bg-blue-800 text-white rounded-md text-xs font-semibold shadow-xs transition-all disabled:opacity-60"
            >
              {isExporting ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : exportSuccess ? (
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-300" />
              ) : (
                <FileDown className="w-3.5 h-3.5" />
              )}
              <span>{isExporting ? 'Exporting...' : exportSuccess ? 'Exported!' : 'Export to DOCX'}</span>
            </button>

            <button
              type="button"
              onClick={onClose}
              className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-200/80 rounded-md transition-colors"
              title="Close report modal"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Scrollable Report Sheet View */}
        <div className="flex-1 overflow-y-auto p-6 sm:p-8 bg-slate-100/60 custom-scrollbar">
          <div className="max-w-3xl mx-auto bg-white border border-slate-200 rounded-lg shadow-sm p-8 sm:p-10 text-slate-800 font-sans space-y-8">
            {/* Report Header */}
            <div className="border-b border-slate-200 pb-5">
              <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono uppercase tracking-wider mb-2">
                <span>Enterprise Decision Support</span>
                <span>Confidential · Internal Executive Use</span>
              </div>
              <h1 className="text-2xl font-bold text-slate-900 tracking-tight leading-snug">
                Management Commentary: Gross Margin Performance
              </h1>
              <div className="flex flex-wrap items-center gap-y-1 gap-x-4 mt-2.5 text-xs text-slate-500 font-mono">
                <span>
                  <strong>Actual Period:</strong> {actualStartDate} to {actualEndDate}
                </span>
                <span>•</span>
                <span>
                  <strong>Comparable:</strong> {comparableStartDate} to {comparableEndDate}
                </span>
              </div>
            </div>

            {/* Section 1: 1. Performance Overview */}
            <section className="space-y-3">
              <div className="flex items-center gap-2 border-b border-slate-100 pb-1.5">
                <TrendingUp className="w-4 h-4 text-blue-600" />
                <h2 className="text-base font-bold text-slate-900">
                  1. Performance Overview
                </h2>
              </div>
              <p className="text-xs text-slate-600 leading-relaxed">
                This executive commentary provides senior manufacturing and finance leadership with an exhaustive variance synthesis for the evaluated cycle. Leveraging the Dual-BOM reconciliation engine, financial variance is decoupled across structural bill-of-materials shifts, scrap rate deviations, and procurement pricing trends.
              </p>

              {/* Dynamic Context Card if node is inspected */}
              {selectedNode && (
                <div className="bg-slate-50 border border-slate-200 rounded-md p-3.5 text-xs space-y-2">
                  <div className="flex items-center justify-between font-mono text-[11px]">
                    <span className="font-bold text-slate-700">
                      Component Focus: {selectedNode.name} ({selectedNode.id})
                    </span>
                    <span className="text-slate-500">ECN: {selectedNode.ecn}</span>
                  </div>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 font-mono text-[11px]">
                    <div className="bg-white p-2 rounded border border-slate-200">
                      <span className="text-slate-400 block text-[10px]">Std Qty</span>
                      <span className="font-semibold text-slate-800">{formatQty(selectedNode.standardQty)}</span>
                    </div>
                    <div className="bg-white p-2 rounded border border-slate-200">
                      <span className="text-slate-400 block text-[10px]">Actual Issued</span>
                      <span className="font-semibold text-slate-800">
                        {formatQty(selectedNode.actualQty)}{' '}
                        <span className="text-[10px] text-slate-500">
                          ({selectedNode.quantityDeltaPercent > 0 ? '+' : ''}{selectedNode.quantityDeltaPercent.toFixed(1)}%)
                        </span>
                      </span>
                    </div>
                    <div className="bg-white p-2 rounded border border-slate-200">
                      <span className="text-slate-400 block text-[10px]">Baseline Cost</span>
                      <span className="font-semibold text-slate-800">{formatCurrency(selectedNode.baselineCost)}</span>
                    </div>
                    <div className="bg-white p-2 rounded border border-slate-200">
                      <span className="text-slate-400 block text-[10px]">Net Variance</span>
                      <span className={`font-bold ${(selectedNode.costDelta ?? 0) > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                        {formatCurrency(selectedNode.costDelta)}
                      </span>
                    </div>
                  </div>
                </div>
              )}

              {/* AI synthesis if present */}
              {report?.summary && (
                <div className="bg-blue-50/70 border border-blue-200/80 rounded-md p-3 text-xs text-blue-900 leading-relaxed">
                  <span className="font-bold flex items-center gap-1 text-blue-800 mb-1">
                    <Sparkles className="w-3.5 h-3.5 text-blue-600" />
                    AI Root-Cause Diagnostic Summary ({report.categoryLabel}):
                  </span>
                  <p>{report.summary}</p>
                </div>
              )}
            </section>

            {/* Section 2: 2. Margin Walk / Bridge Analysis */}
            <section className="space-y-3.5">
              <div className="flex items-center gap-2 border-b border-slate-100 pb-1.5">
                <Layers className="w-4 h-4 text-blue-600" />
                <h2 className="text-base font-bold text-slate-900">
                  2. Margin Walk / Bridge Analysis
                </h2>
              </div>

              {/* Required Placeholder Box */}
              <div className="bg-blue-50 border border-blue-200 rounded-md p-3 text-xs text-blue-950 font-medium">
                <span className="font-bold text-blue-800 block mb-1">
                  Section Coverage / Placeholder:
                </span>
                <p className="font-mono text-[11px] text-blue-900">
                  Volume Impact \ Selling Price \ Sales Mix \ Purchase Price Variance (PPV) \ Manufacturing Efficiency & Yield \ Overhead Under-Absorption
                </p>
              </div>

              {/* Detailed 6 pillars */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1 text-xs">
                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.1 Volume Impact</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Evaluates fixed-cost leverage and scale economics resulting from shifts in total production throughput and sales volume against planned expectations.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.2 Selling Price</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Reflects the direct impact of Average Selling Price (ASP) adjustments, contractual renegotiations, discounts, and realized spot pricing.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.3 Sales Mix</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Decomposes gross margin variance resulting from changes in the portfolio proportion of high-margin versus low-margin assemblies.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.4 Purchase Price Variance (PPV)</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Tracks procurement deviations between actual direct material purchase costs and standard standard baseline costs across supplier agreements.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.5 Manufacturing Efficiency & Yield</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Assesses shop-floor execution performance, including thermal scrap rates, machining rework concessions, cycle-time slippage, and process drift.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md">
                  <h4 className="font-bold text-slate-900 mb-1">2.6 Overhead Under-Absorption</h4>
                  <p className="text-slate-600 text-[11px] leading-relaxed">
                    Monitors over- or under-absorption of plant overhead, machine depreciation, and fixed factory burden driven by utilization and downtime.
                  </p>
                </div>
              </div>
            </section>

            {/* Section 3: 3. Dimensional Deep-Dives */}
            <section className="space-y-3.5">
              <div className="flex items-center gap-2 border-b border-slate-100 pb-1.5">
                <Globe className="w-4 h-4 text-blue-600" />
                <h2 className="text-base font-bold text-slate-900">
                  3. Dimensional Deep-Dives
                </h2>
              </div>

              {/* Required Placeholder Box */}
              <div className="bg-blue-50 border border-blue-200 rounded-md p-3 text-xs text-blue-950 font-medium">
                <span className="font-bold text-blue-800 block mb-1">
                  Section Coverage / Placeholder:
                </span>
                <p className="font-mono text-[11px] text-blue-900">
                  By Product Category / Product Family, By Customer / Channel, By Geographic Region / Market
                </p>
              </div>

              {/* Detailed 3 dimensions */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1 text-xs">
                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md space-y-1">
                  <div className="flex items-center gap-1.5 font-bold text-slate-900">
                    <Layers className="w-3.5 h-3.5 text-blue-600 shrink-0" />
                    <span>By Product Category / Product Family</span>
                  </div>
                  <p className="text-slate-600 text-[11px] leading-relaxed pt-1">
                    Drills into margin performance across product families and sub-assembly clusters to identify systemic BOM cost drift.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md space-y-1">
                  <div className="flex items-center gap-1.5 font-bold text-slate-900">
                    <Users className="w-3.5 h-3.5 text-blue-600 shrink-0" />
                    <span>By Customer / Channel</span>
                  </div>
                  <p className="text-slate-600 text-[11px] leading-relaxed pt-1">
                    Segments contribution margins across key strategic accounts, OEM partners, and direct vs. distributor channels.
                  </p>
                </div>

                <div className="p-3 bg-slate-50 border border-slate-200 rounded-md space-y-1">
                  <div className="flex items-center gap-1.5 font-bold text-slate-900">
                    <Building2 className="w-3.5 h-3.5 text-blue-600 shrink-0" />
                    <span>By Geographic Region / Market</span>
                  </div>
                  <p className="text-slate-600 text-[11px] leading-relaxed pt-1">
                    Analyzes regional freight logistics, tariff exposures, currency adjustments, and localized production footprint costs.
                  </p>
                </div>
              </div>
            </section>
          </div>
        </div>

        {/* Modal Footer */}
        <div className="px-6 py-3 bg-slate-50 border-t border-slate-200 flex items-center justify-between text-xs shrink-0">
          <span className="text-slate-500 font-mono text-[11px]">
            HMLV MarginTrace Attribution Engine v4.8.2
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1 bg-white hover:bg-slate-100 text-slate-700 border border-slate-300 rounded font-medium transition-colors"
            >
              Close
            </button>
            <button
              type="button"
              onClick={handleExportDocx}
              disabled={isExporting}
              className="px-3 py-1 bg-blue-600 hover:bg-blue-700 text-white rounded font-medium flex items-center gap-1.5 shadow-xs transition-colors"
            >
              <FileDown className="w-3.5 h-3.5" />
              <span>{isExporting ? 'Exporting...' : 'Export to DOCX'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
