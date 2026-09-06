import React, { useState, useEffect } from 'react';
import {
  Network,
  Database,
  Settings,
  Layers,
  FileText,
  DollarSign,
  Maximize2,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import { DataPreparation } from './DataPreparation';

const getInitialRoute = (): string => {
  const path = window.location.pathname;
  if (path === '/data-prep') {
    return '/data-prep';
  }
  return '/bom-topology';
};

export const App: React.FC = () => {
  const [currentRoute, setCurrentRoute] = useState<string>(getInitialRoute);

  useEffect(() => {
    // Default route into /bom-topology if accessed via root
    if (window.location.pathname === '/' || window.location.pathname === '') {
      window.history.replaceState({}, '', '/bom-topology');
    }

    const handlePopState = () => {
      setCurrentRoute(window.location.pathname === '/data-prep' ? '/data-prep' : '/bom-topology');
    };

    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const navigate = (path: string) => {
    window.history.pushState({}, '', path);
    setCurrentRoute(path);
  };

  return (
    <div className="flex flex-col h-screen w-screen bg-slate-50 text-slate-800 font-sans select-none overflow-hidden">
      {/* 1. Top Bar (Reserved) */}
      <header className="h-12 bg-white border-b border-slate-200 px-6 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2 text-xs text-slate-400">
          <span className="font-semibold text-slate-700">MarginTrace</span>
          <span>/</span>
          <span className="text-slate-600 font-medium">
            {currentRoute === '/data-prep' ? 'Data Preparation' : 'BOM Topology Canvas'}
          </span>
          <span>/</span>
          <span>Top Bar (Reserved)</span>
        </div>
        <div className="text-xs text-slate-300 font-mono">
          [Top Bar Placeholder]
        </div>
      </header>

      {/* Main Layout (Sidebar + Content Area) */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* 2. Left Sidebar Navigation */}
        <aside className="w-56 bg-white border-r border-slate-200 flex flex-col justify-between shrink-0">
          <div className="p-3">
            <div className="px-3 py-2 text-xs font-semibold text-slate-400 uppercase tracking-wider">
              Navigation
            </div>
            <nav className="space-y-1">
              {/* BOM Topology Canvas -> /bom-topology */}
              <button
                onClick={() => navigate('/bom-topology')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/bom-topology'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Network className="w-4 h-4 text-blue-600 shrink-0" />
                <span>BOM Topology Canvas</span>
              </button>

              {/* Data Preparation -> /data-prep */}
              <button
                onClick={() => navigate('/data-prep')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/data-prep'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Database className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Data Preparation</span>
              </button>

              {/* Settings & Parameters */}
              <button
                onClick={() => navigate('/settings')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs font-medium transition-colors ${
                  currentRoute === '/settings'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50'
                }`}
              >
                <Settings className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Settings & Parameters</span>
              </button>
            </nav>
          </div>

          <div className="p-3 border-t border-slate-100 text-[11px] text-slate-400 text-center font-mono">
            HMLV Decision Platform
          </div>
        </aside>

        {/* Dynamic Route Content */}
        {currentRoute === '/data-prep' ? (
          /* ========================================================================= */
          /* PAGE: Data Preparation                                                    */
          /* ========================================================================= */
          <DataPreparation />
        ) : (
          /* ========================================================================= */
          /* PAGE: BOM Topology Canvas (Default: Canvas Center + Detail Panel Right)   */
          /* ========================================================================= */
          <div className="flex-1 flex min-w-0 h-full overflow-hidden">
            {/* 3. Center: Main BOM Topology Canvas Workspace */}
            <main className="flex-1 relative bg-slate-50 flex flex-col overflow-hidden">
              {/* Floating Canvas Controls */}
              <div className="absolute top-4 left-4 z-10 bg-white border border-slate-200 rounded-md shadow-xs px-3 py-1.5 flex items-center gap-2 text-xs text-slate-600">
                <span className="font-semibold text-slate-800">Canvas Workspace</span>
                <span className="text-slate-300">|</span>
                <button className="p-1 hover:text-slate-900 text-slate-500" title="Zoom In">
                  <ZoomIn className="w-3.5 h-3.5" />
                </button>
                <button className="p-1 hover:text-slate-900 text-slate-500" title="Zoom Out">
                  <ZoomOut className="w-3.5 h-3.5" />
                </button>
                <button className="p-1 hover:text-slate-900 text-slate-500" title="Fit View">
                  <Maximize2 className="w-3.5 h-3.5" />
                </button>
              </div>

              {/* Central Canvas Framework */}
              <div className="flex-1 w-full h-full flex items-center justify-center p-8">
                <div className="w-full h-full border-2 border-dashed border-slate-200 rounded-xl bg-white/70 flex flex-col items-center justify-center text-slate-400 gap-3">
                  <Network className="w-12 h-12 text-slate-300 stroke-1" />
                  <div className="text-center">
                    <p className="text-sm font-semibold text-slate-600">BOM Topology Main View</p>
                    <p className="text-xs text-slate-400 mt-1">
                      Route: <code className="bg-slate-100 px-1.5 py-0.5 rounded text-blue-600 font-mono">/bom-topology</code> (Default Landing Page)
                    </p>
                  </div>
                </div>
              </div>
            </main>

            {/* 4. Right: Detail Panel */}
            <aside className="w-80 bg-white border-l border-slate-200 flex flex-col shrink-0 overflow-y-auto">
              <div className="h-11 px-4 border-b border-slate-200 flex items-center">
                <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Details
                </h3>
              </div>

              <div className="p-4 space-y-4">
                {/* Section 1: Basic Information */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <FileText className="w-4 h-4 text-blue-600" />
                    <span>Basic Information</span>
                  </div>
                  <div className="space-y-1.5 text-xs">
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Item / Part Name</span>
                      <span className="font-medium text-slate-700">[Pending Item]</span>
                    </div>
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Part Code / Drawing</span>
                      <span className="font-mono text-slate-700">[PART-CODE]</span>
                    </div>
                    <div className="flex justify-between py-1 border-b border-slate-100">
                      <span className="text-slate-400">Level / Category</span>
                      <span className="text-slate-700">[Level / Category]</span>
                    </div>
                    <div className="flex justify-between py-1">
                      <span className="text-slate-400">Work Center / Owner</span>
                      <span className="text-slate-700">[Work Center]</span>
                    </div>
                  </div>
                </section>

                {/* Section 2: Cost Breakdown (M / L / O) */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2.5">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <DollarSign className="w-4 h-4 text-emerald-600" />
                    <span>Cost Breakdown</span>
                  </div>
                  <div className="space-y-2 text-xs font-mono">
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-sky-500" />
                        Material (M)
                      </span>
                      <span className="font-semibold text-slate-800">$0.00</span>
                    </div>
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-amber-500" />
                        Labor (L)
                      </span>
                      <span className="font-semibold text-slate-800">$0.00</span>
                    </div>
                    <div className="flex items-center justify-between p-2 rounded bg-white border border-slate-200/80">
                      <span className="text-slate-600 flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-violet-500" />
                        Overhead (O)
                      </span>
                      <span className="font-semibold text-slate-800">$0.00</span>
                    </div>
                    <div className="pt-1.5 border-t border-slate-200 flex justify-between text-xs font-bold text-slate-900">
                      <span>Total Cost</span>
                      <span>$0.00 NZD</span>
                    </div>
                  </div>
                </section>

                {/* Section 3: Direct Dependencies & Operations */}
                <section className="bg-slate-50/70 border border-slate-200 rounded-lg p-3.5 space-y-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-slate-800 pb-1.5 border-b border-slate-200/60">
                    <Layers className="w-4 h-4 text-indigo-600" />
                    <span>Direct Dependencies & Operations</span>
                  </div>
                  <div className="border border-dashed border-slate-200 rounded p-4 text-center text-xs text-slate-400 bg-white">
                    [Direct downstream components and routing steps]
                  </div>
                </section>
              </div>
            </aside>
          </div>
        )}
      </div>
    </div>
  );
};

export default App;
