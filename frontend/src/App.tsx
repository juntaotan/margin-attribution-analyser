import React, { useState, useEffect } from 'react';
import { Network, Database, Settings } from 'lucide-react';
import { DataPreparation } from './DataPreparation';
import { DualBomAnalysisPage } from './components/DualBom/DualBomAnalysisPage';

const getInitialRoute = (): string => {
  const path = window.location.pathname;
  if (path === '/data-prep' || path === '/settings') {
    return path;
  }
  return '/margin-topology';
};

export const App: React.FC = () => {
  const [currentRoute, setCurrentRoute] = useState<string>(getInitialRoute);

  useEffect(() => {
    // Default route into /margin-topology if accessed via root or legacy /bom-topology
    if (
      window.location.pathname === '/' ||
      window.location.pathname === '' ||
      window.location.pathname === '/bom-topology'
    ) {
      window.history.replaceState({}, '', '/margin-topology');
    }

    const handlePopState = () => {
      setCurrentRoute(getInitialRoute());
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
      {/* 1. Global Navigation Top Bar */}
      <header className="h-11 bg-white border-b border-slate-200 px-6 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2 text-xs text-slate-400">
          <span className="font-bold text-slate-800 tracking-tight text-sm">
            MarginTrace
          </span>
          <span>/</span>
          <span className="text-slate-600 font-medium">
            {currentRoute === '/data-prep'
              ? 'Data Preparation'
              : currentRoute === '/settings'
              ? 'Settings & Parameters'
              : 'Dual-BOM MAS Variance Analysis'}
          </span>
        </div>
        <div className="text-xs text-slate-400 font-mono flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-emerald-500" />
          <span>HMLV Decision Platform</span>
        </div>
      </header>

      {/* Main Layout (Sidebar + Content Area) */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* 2. Left Sidebar Navigation */}
        <aside className="w-56 bg-white border-r border-slate-200 flex flex-col justify-between shrink-0">
          <div className="p-3">
            <div className="px-3 py-2 text-[10px] font-bold text-slate-400 uppercase tracking-wider">
              Navigation
            </div>
            <nav className="space-y-1">
              {/* Margin Topology Analysis -> /margin-topology */}
              <button
                type="button"
                onClick={() => navigate('/margin-topology')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs transition-colors ${
                  currentRoute === '/margin-topology'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50 font-medium'
                }`}
              >
                <Network className="w-4 h-4 text-blue-600 shrink-0" />
                <span>Dual-BOM Analysis</span>
              </button>

              {/* Data Preparation -> /data-prep */}
              <button
                type="button"
                onClick={() => navigate('/data-prep')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs transition-colors ${
                  currentRoute === '/data-prep'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50 font-medium'
                }`}
              >
                <Database className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Data Preparation</span>
              </button>

              {/* Settings & Parameters */}
              <button
                type="button"
                onClick={() => navigate('/settings')}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs transition-colors ${
                  currentRoute === '/settings'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200/80 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-50 font-medium'
                }`}
              >
                <Settings className="w-4 h-4 text-slate-500 shrink-0" />
                <span>Settings &amp; Parameters</span>
              </button>
            </nav>
          </div>

          <div className="p-3 border-t border-slate-100 text-[11px] text-slate-400 text-center font-mono">
            HMLV Engine v4.8.2
          </div>
        </aside>

        {/* Dynamic Route Content */}
        {currentRoute === '/data-prep' ? (
          <DataPreparation />
        ) : currentRoute === '/settings' ? (
          <main className="flex-1 bg-slate-50 flex items-center justify-center text-slate-400 text-xs font-mono">
            [Settings &amp; Parameters Panel Placeholder]
          </main>
        ) : (
          <DualBomAnalysisPage />
        )}
      </div>
    </div>
  );
};

export default App;
