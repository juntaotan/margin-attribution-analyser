import React, { useState } from 'react';
import {
  Heading1,
  Heading2,
  Bold,
  Minus,
  Highlighter,
  PlusCircle,
  Eye,
  Edit3,
  FileDown,
  RotateCcw,
  Sparkles,
  CheckCircle2,
  Database,
  Calculator,
  Filter,
  Layers,
  FileText,
} from 'lucide-react';
import { Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType } from 'docx';
import {
  ReportDocument,
  ReportBlock,
  PlaceholderToken,
  SemanticExecutionPlan,
  BlockType,
} from './types';
import { createDefaultDocument } from './defaultTemplate';
import { parseSemanticPrompt, executeSemanticQuery } from './semanticParser';

export const ReportStudioPage: React.FC = () => {
  // Document State
  const [doc, setDoc] = useState<ReportDocument>(createDefaultDocument);
  // View Mode: 'edit' (shows placeholder tokens) vs 'render' (shows calculated values)
  const [viewMode, setViewMode] = useState<'edit' | 'render'>('edit');
  // Currently selected token for inspection in secondary column
  const [activeTokenId, setActiveTokenId] = useState<string>('token_ppv');
  // Currently active block in main column
  const [activeBlockId, setActiveBlockId] = useState<string>('b5');

  // New placeholder dialog state
  const [isAddingToken, setIsAddingToken] = useState(false);
  const [newTokenName, setNewTokenName] = useState('');

  // Secondary column prompt input buffer
  const activeToken = doc.tokens[activeTokenId];
  const [promptInput, setPromptInput] = useState<string>(activeToken?.prompt || '');
  const [isExporting, setIsExporting] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);

  // When activeTokenId changes, sync the prompt buffer
  React.useEffect(() => {
    if (activeToken) {
      setPromptInput(activeToken.prompt);
    }
  }, [activeTokenId]);

  // Context for semantic parser
  const parserContext = {
    materialId: doc.materialFocus,
    actualStartDate: doc.periodActual.split(' to ')[0] || '2026-08-01',
    actualEndDate: doc.periodActual.split(' to ')[1] || '2026-08-31',
  };

  // Re-parse when promptInput changes in secondary column
  const currentSemanticPlan: SemanticExecutionPlan | undefined = activeToken
    ? parseSemanticPrompt(promptInput || activeToken.prompt, parserContext)
    : undefined;

  // Handle Tool: Add/Insert Block
  const handleAddBlock = (type: BlockType) => {
    const newId = `b_${Date.now()}`;
    const newBlock: ReportBlock = {
      id: newId,
      type,
      content:
        type === 'h1'
          ? 'New Primary Heading'
          : type === 'h2'
          ? 'New Subheading'
          : type === 'divider'
          ? ''
          : 'Enter report body text here. You can insert dynamic data placeholders from the top toolbar...',
      bold: type === 'h1' || type === 'h2',
    };
    const blockIndex = doc.blocks.findIndex((b) => b.id === activeBlockId);
    const updatedBlocks = [...doc.blocks];
    if (blockIndex >= 0) {
      updatedBlocks.splice(blockIndex + 1, 0, newBlock);
    } else {
      updatedBlocks.push(newBlock);
    }
    setDoc({ ...doc, blocks: updatedBlocks });
    setActiveBlockId(newId);
  };

  // Handle Tool: Toggle Bold on Active Block
  const handleToggleBold = () => {
    setDoc({
      ...doc,
      blocks: doc.blocks.map((b) =>
        b.id === activeBlockId ? { ...b, bold: !b.bold } : b
      ),
    });
  };

  // Handle Tool: Toggle Highlight on Active Block
  const handleToggleHighlight = () => {
    setDoc({
      ...doc,
      blocks: doc.blocks.map((b) =>
        b.id === activeBlockId ? { ...b, highlight: !b.highlight } : b
      ),
    });
  };

  // Handle Insert Placeholder Token
  const handleConfirmInsertToken = () => {
    if (!newTokenName.trim()) return;
    const tokenId = `token_${Date.now()}`;
    const defaultPrompt = `Query financial metric for ${newTokenName} and compute variance`;
    const initialPlan = parseSemanticPrompt(defaultPrompt, parserContext);
    const initialQuery = executeSemanticQuery(initialPlan);

    const newToken: PlaceholderToken = {
      id: tokenId,
      label: newTokenName.trim(),
      prompt: defaultPrompt,
      status: 'analyzed',
      semanticPlan: initialPlan,
      resolvedValue: initialQuery.value,
      unit: initialQuery.unit,
      updatedAt: 'Just now',
    };

    // Insert into active block's content
    const updatedBlocks = doc.blocks.map((b) => {
      if (b.id === activeBlockId) {
        return {
          ...b,
          content: `${b.content} {{${tokenId}}} `,
        };
      }
      return b;
    });

    setDoc({
      ...doc,
      blocks: updatedBlocks,
      tokens: {
        ...doc.tokens,
        [tokenId]: newToken,
      },
    });

    setActiveTokenId(tokenId);
    setIsAddingToken(false);
    setNewTokenName('');
  };

  // Handle Update Block Content (Typing in canvas)
  const handleBlockContentChange = (blockId: string, content: string) => {
    setDoc({
      ...doc,
      blocks: doc.blocks.map((b) => (b.id === blockId ? { ...b, content } : b)),
    });
  };

  // Handle Secondary Column: Test & Execute Query
  const handleExecuteQuery = () => {
    if (!activeToken || !currentSemanticPlan) return;
    const queryResult = executeSemanticQuery(currentSemanticPlan);
    const updatedToken: PlaceholderToken = {
      ...activeToken,
      prompt: promptInput,
      status: 'executed',
      semanticPlan: currentSemanticPlan,
      resolvedValue: queryResult.value,
      unit: queryResult.unit,
      updatedAt: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    setDoc({
      ...doc,
      tokens: {
        ...doc.tokens,
        [activeTokenId]: updatedToken,
      },
    });
  };

  // Quick Preset Prompts
  const presetPrompts = [
    {
      label: 'PPV Deviation',
      prompt: 'Query actual purchase order settlement prices vs baseline standard costs to derive PPV deviation rate',
    },
    {
      label: 'Material Overuse',
      prompt: 'Analyze shop-floor material dispatches against work order planned BOM quotas to compute overuse rate',
    },
    {
      label: 'Scrap Losses',
      prompt: 'Aggregate defect and scrap write-offs in production logs to evaluate net scrap financial impact',
    },
    {
      label: 'ECN BOM Quota',
      prompt: 'Compare active ECN design bill of materials against baseline revision to calculate unit quota cost impact',
    },
  ];

  // DOCX Export
  const handleExportDocx = async () => {
    try {
      setIsExporting(true);
      const paragraphs: Paragraph[] = [
        new Paragraph({
          text: doc.title,
          heading: HeadingLevel.TITLE,
          alignment: AlignmentType.CENTER,
          spacing: { after: 200 },
        }),
        new Paragraph({
          text: `Audit Period: ${doc.periodActual} | Baseline: ${doc.periodComparable} | Material: ${doc.materialFocus}`,
          alignment: AlignmentType.CENTER,
          spacing: { after: 400 },
        }),
      ];

      doc.blocks.forEach((b) => {
        if (b.type === 'divider') {
          paragraphs.push(
            new Paragraph({
              text: '--------------------------------------------------',
              alignment: AlignmentType.CENTER,
              spacing: { before: 150, after: 150 },
            })
          );
          return;
        }

        // Replace tokens in block content
        let textContent = b.content;
        Object.entries(doc.tokens).forEach(([tid, token]) => {
          const val = token.resolvedValue || `[Pending: ${token.label}]`;
          textContent = textContent.split(`{{${tid}}}`).join(val);
        });

        if (b.type === 'h1') {
          paragraphs.push(
            new Paragraph({
              text: textContent,
              heading: HeadingLevel.HEADING_1,
              spacing: { before: 300, after: 120 },
            })
          );
        } else if (b.type === 'h2') {
          paragraphs.push(
            new Paragraph({
              text: textContent,
              heading: HeadingLevel.HEADING_2,
              spacing: { before: 200, after: 100 },
            })
          );
        } else {
          paragraphs.push(
            new Paragraph({
              spacing: { after: 140 },
              children: [
                new TextRun({
                  text: textContent,
                  bold: b.bold,
                  highlight: b.highlight ? 'yellow' : undefined,
                }),
              ],
            })
          );
        }
      });

      const wordDoc = new Document({
        sections: [
          {
            properties: {
              page: {
                margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 },
              },
            },
            children: paragraphs,
          },
        ],
      });

      const blob = await Packer.toBlob(wordDoc);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `Management_Commentary_${Date.now()}.docx`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 2500);
    } catch (e) {
      console.error('Failed to export docx:', e);
    } finally {
      setIsExporting(false);
    }
  };

  /**
   * Render text with interactive placeholder tokens
   */
  const renderInteractiveContent = (block: ReportBlock) => {
    const parts = block.content.split(/(\{\{[^}]+\}\})/g);

    return parts.map((part, index) => {
      const match = part.match(/^\{\{([^}]+)\}\}$/);
      if (match) {
        const tokenId = match[1];
        const token = doc.tokens[tokenId];

        if (!token) {
          return (
            <span
              key={index}
              className="inline-flex items-center px-1.5 py-0.5 rounded bg-slate-200 text-slate-500 font-mono text-xs"
            >
              [Undefined: {tokenId}]
            </span>
          );
        }

        const isSelected = activeTokenId === tokenId;

        // In Live Render Mode: Render real calculated value with verifiable tag
        if (viewMode === 'render') {
          return (
            <span
              key={index}
              title={`Source: ${token.semanticPlan?.sourceTable || 'Warehouse'} | Formula: ${token.semanticPlan?.formula || 'Direct'}`}
              className="inline-flex items-baseline mx-1 px-1.5 py-0.5 rounded bg-blue-50 border border-blue-200 text-blue-900 font-semibold font-mono text-xs shadow-2xs group relative cursor-pointer"
              onClick={() => {
                setViewMode('edit');
                setActiveTokenId(tokenId);
              }}
            >
              <span>{token.resolvedValue || token.label}</span>
              <span className="ml-1 text-[10px] text-blue-500 font-normal group-hover:underline">
                [Traceable]
              </span>
            </span>
          );
        }

        // In Edit Mode: Render interactive placeholder pill
        return (
          <button
            key={index}
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setActiveTokenId(tokenId);
              setActiveBlockId(block.id);
            }}
            className={`inline-flex items-center gap-1.5 mx-1 my-0.5 px-2.5 py-0.5 rounded-full text-xs font-medium font-mono transition-all shadow-2xs ${
              isSelected
                ? 'bg-blue-600 text-white ring-2 ring-blue-300 ring-offset-1 scale-105'
                : token.status === 'executed'
                ? 'bg-emerald-50 text-emerald-800 border border-emerald-300 hover:bg-emerald-100'
                : 'bg-amber-50 text-amber-800 border border-amber-300 hover:bg-amber-100'
            }`}
          >
            <Sparkles
              className={`w-3 h-3 ${isSelected ? 'text-white' : 'text-blue-500'}`}
            />
            <span>{`{{${token.label}}}`}</span>
            {token.resolvedValue && (
              <span
                className={`text-[10px] px-1 rounded ${
                  isSelected ? 'bg-blue-700 text-blue-100' : 'bg-emerald-200/70 text-emerald-900'
                }`}
              >
                {token.resolvedValue}
              </span>
            )}
          </button>
        );
      }

      // Plain text part
      return <span key={index}>{part}</span>;
    });
  };

  return (
    <div className="flex flex-col h-full w-full bg-slate-100 overflow-hidden font-sans">
      {/* 1. Top Formatting Toolbar & Action Bar */}
      <header className="h-13 bg-white border-b border-slate-200 px-5 flex items-center justify-between shrink-0 shadow-2xs z-10">
        {/* Left Toolbar Tools */}
        <div className="flex items-center gap-1 sm:gap-2">
          <div className="flex items-center border border-slate-200 rounded-lg p-0.5 bg-slate-50/80">
            <button
              type="button"
              onClick={() => handleAddBlock('h1')}
              title="Add Heading 1 (H1)"
              className="p-1.5 rounded hover:bg-white hover:shadow-2xs text-slate-700 text-xs font-bold transition-all flex items-center gap-1"
            >
              <Heading1 className="w-4 h-4 text-slate-700" />
            </button>
            <button
              type="button"
              onClick={() => handleAddBlock('h2')}
              title="Add Heading 2 (H2)"
              className="p-1.5 rounded hover:bg-white hover:shadow-2xs text-slate-700 text-xs font-bold transition-all flex items-center gap-1"
            >
              <Heading2 className="w-4 h-4 text-slate-700" />
            </button>
            <button
              type="button"
              onClick={handleToggleBold}
              title="Toggle Bold"
              className="p-1.5 rounded hover:bg-white hover:shadow-2xs text-slate-700 transition-all"
            >
              <Bold className="w-4 h-4 text-slate-700" />
            </button>
            <button
              type="button"
              onClick={() => handleAddBlock('divider')}
              title="Insert Divider"
              className="p-1.5 rounded hover:bg-white hover:shadow-2xs text-slate-700 transition-all"
            >
              <Minus className="w-4 h-4 text-slate-700" />
            </button>
            <button
              type="button"
              onClick={handleToggleHighlight}
              title="Highlight Paragraph"
              className="p-1.5 rounded hover:bg-amber-100 text-amber-700 transition-all"
            >
              <Highlighter className="w-4 h-4 text-amber-600" />
            </button>
          </div>

          <div className="h-6 w-px bg-slate-200 mx-1" />

          {/* Core Action: Insert Data Placeholder */}
          <button
            type="button"
            onClick={() => setIsAddingToken(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white rounded-lg text-xs font-semibold shadow-xs transition-all cursor-pointer"
          >
            <PlusCircle className="w-3.5 h-3.5" />
            <span>Insert Data Placeholder</span>
          </button>
        </div>

        {/* Right Action Tools: Mode Toggle & Export */}
        <div className="flex items-center gap-2.5">
          {/* Mode Toggle Switch */}
          <div className="flex items-center bg-slate-100 p-0.5 rounded-lg border border-slate-200">
            <button
              type="button"
              onClick={() => setViewMode('edit')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-all ${
                viewMode === 'edit'
                  ? 'bg-white text-blue-700 shadow-2xs font-semibold'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              <Edit3 className="w-3.5 h-3.5" />
              <span>Edit Mode</span>
            </button>
            <button
              type="button"
              onClick={() => setViewMode('render')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-all ${
                viewMode === 'render'
                  ? 'bg-white text-emerald-700 shadow-2xs font-semibold'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              <Eye className="w-3.5 h-3.5" />
              <span>Live Render Report</span>
            </button>
          </div>

          <div className="h-5 w-px bg-slate-200" />

          {/* Reset Template */}
          <button
            type="button"
            onClick={() => setDoc(createDefaultDocument())}
            title="Reset to Default Template"
            className="p-1.5 text-slate-500 hover:text-slate-800 hover:bg-slate-200/70 rounded-md transition-colors"
          >
            <RotateCcw className="w-4 h-4" />
          </button>

          {/* Export to Word */}
          <button
            type="button"
            onClick={handleExportDocx}
            disabled={isExporting}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-900 active:bg-black text-white rounded-lg text-xs font-medium shadow-2xs transition-all disabled:opacity-50"
          >
            {exportSuccess ? (
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            ) : (
              <FileDown className="w-3.5 h-3.5" />
            )}
            <span>{isExporting ? 'Exporting...' : exportSuccess ? 'Exported (.docx)' : 'Export Word'}</span>
          </button>
        </div>
      </header>

      {/* 2. Main Workspace: Two-Column Studio Layout */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* ========================================================= */}
        {/* LEFT COLUMN: Main Column (Document Canvas & Live Render)  */}
        {/* ========================================================= */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 flex justify-center bg-slate-100/80 custom-scrollbar">
          <div className="w-full max-w-3xl bg-white rounded-xl shadow-md border border-slate-200 p-8 sm:p-12 min-h-[850px] flex flex-col space-y-6 transition-all">
            {/* Document Header Metadata */}
            <div className="border-b border-slate-200 pb-5 space-y-2.5">
              <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono uppercase tracking-wider">
                <span className="flex items-center gap-1 text-blue-600 font-bold">
                  <FileText className="w-3.5 h-3.5" />
                  Dual-BOM Management Commentary Studio
                </span>
                <span>{viewMode === 'edit' ? 'Draft Mode · Editable' : 'Published Preview · Live Formatted'}</span>
              </div>
              <h1 className="text-2xl sm:text-3xl font-bold text-slate-900 tracking-tight leading-snug">
                {doc.title}
              </h1>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-1 text-xs text-slate-500 font-mono">
                <span>
                  <strong>Audit Period:</strong> {doc.periodActual}
                </span>
                <span>•</span>
                <span>
                  <strong>Baseline Period:</strong> {doc.periodComparable}
                </span>
                <span>•</span>
                <span className="text-blue-700 bg-blue-50 px-2 py-0.5 rounded border border-blue-200 font-medium">
                  {doc.materialFocus}
                </span>
              </div>
            </div>

            {/* Document Blocks List */}
            <div className="space-y-4 flex-1">
              {doc.blocks.map((block) => {
                const isActive = activeBlockId === block.id;

                if (block.type === 'divider') {
                  return (
                    <div
                      key={block.id}
                      onClick={() => setActiveBlockId(block.id)}
                      className={`py-2 cursor-pointer group ${
                        isActive ? 'ring-2 ring-blue-400 rounded' : ''
                      }`}
                    >
                      <hr className="border-slate-200 group-hover:border-slate-400 transition-colors" />
                    </div>
                  );
                }

                if (block.type === 'h1') {
                  return (
                    <div
                      key={block.id}
                      onClick={() => setActiveBlockId(block.id)}
                      className={`relative group rounded-md p-1 -m-1 transition-all ${
                        isActive && viewMode === 'edit'
                          ? 'ring-2 ring-blue-500 bg-blue-50/20'
                          : 'hover:bg-slate-50'
                      }`}
                    >
                      {viewMode === 'edit' ? (
                        <input
                          type="text"
                          value={block.content}
                          onChange={(e) => handleBlockContentChange(block.id, e.target.value)}
                          className="w-full text-xl sm:text-2xl font-bold text-slate-900 bg-transparent border-none outline-none focus:ring-0 p-0"
                        />
                      ) : (
                        <h2 className="text-xl sm:text-2xl font-bold text-slate-900">
                          {block.content}
                        </h2>
                      )}
                    </div>
                  );
                }

                if (block.type === 'h2') {
                  return (
                    <div
                      key={block.id}
                      onClick={() => setActiveBlockId(block.id)}
                      className={`relative group rounded-md p-1 -m-1 transition-all ${
                        isActive && viewMode === 'edit'
                          ? 'ring-2 ring-blue-500 bg-blue-50/20'
                          : 'hover:bg-slate-50'
                      }`}
                    >
                      {viewMode === 'edit' ? (
                        <input
                          type="text"
                          value={block.content}
                          onChange={(e) => handleBlockContentChange(block.id, e.target.value)}
                          className="w-full text-base sm:text-lg font-bold text-slate-800 bg-transparent border-none outline-none focus:ring-0 p-0"
                        />
                      ) : (
                        <h3 className="text-base sm:text-lg font-bold text-slate-800">
                          {block.content}
                        </h3>
                      )}
                    </div>
                  );
                }

                // Paragraph block with embedded tokens
                return (
                  <div
                    key={block.id}
                    onClick={() => setActiveBlockId(block.id)}
                    className={`relative rounded-lg p-2.5 -m-1 transition-all leading-relaxed text-sm text-slate-700 ${
                      block.highlight ? 'bg-amber-50/90 border border-amber-200/80' : ''
                    } ${block.bold ? 'font-semibold text-slate-900' : ''} ${
                      isActive && viewMode === 'edit'
                        ? 'ring-2 ring-blue-500 bg-blue-50/30'
                        : 'hover:bg-slate-50/80'
                    }`}
                  >
                    {viewMode === 'edit' ? (
                      <div className="space-y-1.5">
                        <div className="text-xs text-slate-400 font-mono flex items-center justify-between">
                          <span>Paragraph Block (with dynamic placeholders)</span>
                          {isActive && (
                            <span className="text-[10px] text-blue-600 bg-blue-50 px-1 rounded">
                              Focused
                            </span>
                          )}
                        </div>
                        {/* Interactive rendered token paragraph */}
                        <div className="leading-relaxed whitespace-pre-wrap">
                          {renderInteractiveContent(block)}
                        </div>
                        {/* In edit mode, allow raw string tweaking if needed */}
                        <details className="text-[11px] text-slate-400 cursor-pointer pt-1">
                          <summary className="hover:text-slate-600">Edit text &amp; token markup</summary>
                          <textarea
                            value={block.content}
                            onChange={(e) => handleBlockContentChange(block.id, e.target.value)}
                            rows={3}
                            className="mt-1 w-full p-2 bg-white border border-slate-200 rounded text-xs font-mono text-slate-800 focus:outline-blue-500"
                          />
                        </details>
                      </div>
                    ) : (
                      <div className="leading-relaxed">{renderInteractiveContent(block)}</div>
                    )}
                  </div>
                );
              })}
            </div>

            {/* Document Footer Signoff */}
            <div className="pt-8 border-t border-slate-200 flex items-center justify-between text-xs text-slate-400 font-mono">
              <span>HMLV MAS Dual-BOM Engine · Management Brief</span>
              <span>Confidential / Internal Use Only</span>
            </div>
          </div>
        </main>

        {/* ========================================================= */}
        {/* RIGHT COLUMN: Secondary Column (AI Sensing & Query Plan)  */}
        {/* ========================================================= */}
        <aside className="w-96 lg:w-[420px] xl:w-[450px] bg-white border-l border-slate-200 flex flex-col shrink-0 overflow-y-auto custom-scrollbar shadow-lg">
          {/* Header */}
          <div className="p-4 bg-slate-50 border-b border-slate-200 flex items-center justify-between shrink-0">
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center font-bold">
                <Sparkles className="w-4 h-4" />
              </div>
              <div>
                <h3 className="text-xs font-bold text-slate-900">
                  AI Semantic Sensing &amp; Query Studio
                </h3>
                <p className="text-[10px] text-slate-500 font-mono">
                  Prompt-to-Query Variable Inspector
                </p>
              </div>
            </div>
            {activeToken && (
              <span
                className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-semibold ${
                  activeToken.status === 'executed'
                    ? 'bg-emerald-100 text-emerald-800'
                    : 'bg-amber-100 text-amber-800'
                }`}
              >
                {activeToken.status === 'executed' ? 'Ready / Executed' : 'Pending Execution'}
              </span>
            )}
          </div>

          {/* Secondary Column Body */}
          <div className="p-4 space-y-4 flex-1">
            {/* 1. Placeholder Selector Bar */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-slate-600 uppercase tracking-wider block">
                Document Data Placeholders
              </label>
              <div className="flex flex-wrap gap-1.5">
                {Object.values(doc.tokens).map((tok) => (
                  <button
                    key={tok.id}
                    type="button"
                    onClick={() => setActiveTokenId(tok.id)}
                    className={`px-2.5 py-1 rounded-md text-xs font-mono transition-all flex items-center gap-1 ${
                      activeTokenId === tok.id
                        ? 'bg-blue-600 text-white shadow-2xs font-semibold'
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>{`{{${tok.label}}}`}</span>
                    {tok.resolvedValue && (
                      <span className="text-[10px] opacity-80">({tok.resolvedValue})</span>
                    )}
                  </button>
                ))}
              </div>
            </div>

            {activeToken ? (
              <>
                {/* 2. Prompt Input Box */}
                <div className="space-y-2 bg-slate-50 p-3.5 rounded-xl border border-slate-200">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                      <Edit3 className="w-3.5 h-3.5 text-blue-600" />
                      AI Prompt Instructions:
                    </span>
                    <span className="text-[10px] text-slate-400 font-mono">
                      {activeToken.id}
                    </span>
                  </div>

                  <textarea
                    rows={3}
                    value={promptInput}
                    onChange={(e) => setPromptInput(e.target.value)}
                    placeholder="Enter natural language instructions (e.g. source table, filter criteria, calculation formula)..."
                    className="w-full p-2.5 bg-white border border-slate-200 rounded-lg text-xs text-slate-800 focus:outline-blue-500 focus:ring-1 focus:ring-blue-500 font-sans leading-relaxed shadow-2xs"
                  />

                  {/* Preset prompt pills */}
                  <div className="pt-1">
                    <span className="text-[10px] text-slate-400 block mb-1">Quick Test Presets:</span>
                    <div className="flex flex-wrap gap-1">
                      {presetPrompts.map((p, idx) => (
                        <button
                          key={idx}
                          type="button"
                          onClick={() => setPromptInput(p.prompt)}
                          className="text-[10px] bg-white hover:bg-blue-50 hover:text-blue-700 text-slate-600 px-2 py-0.5 rounded border border-slate-200 transition-colors"
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>

                {/* 3. AI Execution Blueprint (Transparent Feedback Card) */}
                {currentSemanticPlan && (
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

                    {/* (1) Source Table */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Database className="w-3.5 h-3.5 text-blue-600" />
                        <span>1. Source Data Table:</span>
                      </div>
                      <div className="bg-white p-2 rounded-md border border-slate-200 text-xs font-mono text-blue-900 font-semibold">
                        {currentSemanticPlan.sourceTableLabel}
                      </div>
                    </div>

                    {/* (2) Filter Conditions */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Filter className="w-3.5 h-3.5 text-amber-600" />
                        <span>2. Filter Criteria:</span>
                      </div>
                      <ul className="bg-white p-2 rounded-md border border-slate-200 space-y-1 text-[11px] font-mono text-slate-700">
                        {currentSemanticPlan.filterConditions.map((cond, i) => (
                          <li key={i} className="flex items-center gap-1.5">
                            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
                            <span>{cond}</span>
                          </li>
                        ))}
                      </ul>
                    </div>

                    {/* (3) Input Fields Mapping */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Layers className="w-3.5 h-3.5 text-indigo-600" />
                        <span>3. Input Field Mappings:</span>
                      </div>
                      <div className="bg-white rounded-md border border-slate-200 divide-y divide-slate-100 text-[11px]">
                        {currentSemanticPlan.inputFields.map((f, i) => (
                          <div key={i} className="p-2 space-y-0.5">
                            <div className="flex items-center justify-between font-mono">
                              <span className="font-bold text-indigo-700">{f.field}</span>
                              <span className="text-slate-500 text-[10px]">{f.label}</span>
                            </div>
                            <p className="text-[10px] text-slate-400">{f.description}</p>
                          </div>
                        ))}
                      </div>
                    </div>

                    {/* (4) Formula & Processing */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-1 text-[11px] font-bold text-slate-700">
                        <Calculator className="w-3.5 h-3.5 text-emerald-600" />
                        <span>4. Processing &amp; Computation Formula:</span>
                      </div>
                      <div className="bg-slate-900 text-emerald-300 p-2.5 rounded-md font-mono text-[11px] break-all">
                        {currentSemanticPlan.formula}
                      </div>
                      <p className="text-[11px] text-slate-500 pt-0.5 leading-snug">
                        <strong>Business Semantic:</strong> {currentSemanticPlan.formulaDescription}
                      </p>
                    </div>

                    {/* Explanation */}
                    <div className="p-2.5 bg-blue-50/80 rounded-md border border-blue-200/70 text-[11px] text-blue-900 leading-relaxed">
                      <span className="font-bold block mb-0.5">💡 Auditability &amp; Traceability Guarantee:</span>
                      {currentSemanticPlan.explanation}
                    </div>

                    {/* Test & Execute Button */}
                    <div className="pt-2">
                      <button
                        type="button"
                        onClick={handleExecuteQuery}
                        className="w-full py-2 px-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white rounded-lg text-xs font-semibold shadow-xs flex items-center justify-center gap-1.5 transition-all cursor-pointer"
                      >
                        <Sparkles className="w-3.5 h-3.5 text-blue-200" />
                        <span>⚡ Execute Query &amp; Populate Canvas</span>
                      </button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="p-8 text-center text-slate-400 text-xs">
                Please select or insert a data placeholder in the left column.
              </div>
            )}
          </div>
        </aside>
      </div>

      {/* 3. Modal: Insert New Placeholder */}
      {isAddingToken && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 backdrop-blur-2xs p-4">
          <div className="bg-white rounded-xl shadow-xl border border-slate-200 w-full max-w-sm p-5 space-y-4">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg bg-blue-100 text-blue-700 flex items-center justify-center font-bold">
                <PlusCircle className="w-4 h-4" />
              </div>
              <div>
                <h4 className="text-sm font-bold text-slate-800">Insert Data Placeholder</h4>
                <p className="text-[11px] text-slate-500">Bind variable token for AI prompt and query calculation</p>
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-semibold text-slate-700">
                Placeholder Name (e.g. PPV Deviation Rate)
              </label>
              <input
                type="text"
                autoFocus
                placeholder="e.g. Scrap Financial Loss"
                value={newTokenName}
                onChange={(e) => setNewTokenName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleConfirmInsertToken();
                }}
                className="w-full p-2 border border-slate-300 rounded-lg text-xs focus:outline-blue-500"
              />
            </div>

            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setIsAddingToken(false)}
                className="px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-100 rounded-md"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmInsertToken}
                disabled={!newTokenName.trim()}
                className="px-3 py-1.5 text-xs bg-blue-600 hover:bg-blue-700 text-white rounded-md font-semibold disabled:opacity-50"
              >
                Insert into Document
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
