import React, { useState, useEffect } from 'react';
import { Network, Database, Settings, FileText, PanelLeft, type LucideIcon } from 'lucide-react';
import { DataBrowser } from './DataBrowser';
import { DataPreparation } from './DataPreparation';
import { DualBomAnalysisPage } from './components/DualBom/DualBomAnalysisPage';
import { ReportStudioPage } from './components/ReportStudio/ReportStudioPage';
import { SettingsPage } from './components/Settings/SettingsPage';

const ANALYSIS_ROUTE = '/margin-topology';

const INACTIVE_NAV_CLASS = 'text-slate-600 hover:text-slate-900 hover:bg-slate-100/60 font-medium';

interface NavRoute {
  path: string;
  /** Navigation label. */
  label: string;
  /** Breadcrumb title in the top bar. */
  title: string;
  icon: LucideIcon;
  iconClassName: string;
  activeClassName: string;
  inactiveClassName?: string;
}

/** Navigation entries in sidebar order. */
const ROUTES: NavRoute[] = [
  {
    path: ANALYSIS_ROUTE,
    label: 'Dual-BOM Analysis',
    title: 'Dual-BOM MAS Variance Analysis',
    icon: Network,
    iconClassName: 'text-period-actual',
    activeClassName:
      'bg-period-actual-subtle/70 text-period-actual-dark border border-period-actual-border/40 font-semibold shadow-2xs',
  },
  {
    path: '/report-studio',
    label: 'Report Studio',
    title: 'Management Report Studio',
    icon: FileText,
    iconClassName: 'text-provenance-ai',
    activeClassName:
      'bg-provenance-ai-subtle/70 text-indigo-900 border border-provenance-ai-border/40 font-semibold shadow-2xs',
  },
  {
    path: '/data-prep',
    label: 'Data Preparation',
    title: 'Data Preparation',
    icon: Database,
    iconClassName: 'text-slate-500',
    activeClassName: 'bg-slate-100/90 text-slate-900 border border-slate-200/90 font-semibold shadow-2xs',
  },
  {
    path: '/data-browser',
    label: 'Data Browser',
    title: 'Data Browser',
    icon: Database,
    iconClassName: 'text-slate-500',
    activeClassName: 'bg-slate-100 text-slate-900 font-semibold border border-slate-200/90',
    inactiveClassName: 'text-slate-600 hover:bg-slate-100',
  },
  {
    path: '/settings',
    label: 'Settings & Parameters',
    title: 'Settings & Parameters',
    icon: Settings,
    iconClassName: 'text-slate-500',
    activeClassName: 'bg-slate-100/90 text-slate-900 border border-slate-200/90 font-semibold shadow-2xs',
  },
];

const getInitialRoute = (): string => {
  const path = window.location.pathname;
  return ROUTES.some((route) => route.path === path) ? path : ANALYSIS_ROUTE;
};

/**
 * Tracks the current route in the browser history. The root and the legacy `/bom-topology` path are replaced
 * by the analysis route, and other pages can navigate by pushing a URL and dispatching `popstate`.
 */
function useRoute() {
  const [currentRoute, setCurrentRoute] = useState<string>(getInitialRoute);

  useEffect(() => {
    // Default route into /margin-topology if accessed via root or legacy /bom-topology
    if (
      window.location.pathname === '/' ||
      window.location.pathname === '' ||
      window.location.pathname === '/bom-topology'
    ) {
      window.history.replaceState({}, '', ANALYSIS_ROUTE);
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

  return { currentRoute, navigate };
}

/**
 * Application shell: top bar, collapsible navigation and the current page. The analysis page stays mounted once
 * opened, hidden and inert while another page is shown, so a running analysis keeps its connection and results.
 */
export const App: React.FC = () => {
  const { currentRoute, navigate } = useRoute();
  const [analysisOpened, setAnalysisOpened] = useState(() => getInitialRoute() === ANALYSIS_ROUTE);
  const [analysisStatus, setAnalysisStatus] = useState('');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const onAnalysis = currentRoute === ANALYSIS_ROUTE;

  useEffect(() => {
    if (onAnalysis) setAnalysisOpened(true);
  }, [onAnalysis]);

  return (
    <div className="flex flex-col h-screen w-screen bg-surface-canvas text-slate-800 font-sans overflow-hidden">
      <TopBar
        title={ROUTES.find((route) => route.path === currentRoute)?.title ?? ROUTES[0].title}
        sidebarCollapsed={sidebarCollapsed}
        onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
      />

      <div className="flex-1 flex min-h-0 overflow-hidden">
        <Sidebar
          currentRoute={currentRoute}
          collapsed={sidebarCollapsed}
          analysisStatus={analysisStatus}
          onNavigate={navigate}
        />

        <div className="relative flex flex-1 min-w-0 min-h-0">
          {analysisOpened && (
            <div
              className={`absolute inset-0 flex ${onAnalysis ? '' : 'invisible pointer-events-none'}`}
              aria-hidden={!onAnalysis}
              {...(!onAnalysis ? { inert: '' } : {})}
            >
              <DualBomAnalysisPage active={onAnalysis} onStatusChange={setAnalysisStatus} />
            </div>
          )}
          {currentRoute === '/data-browser' ? (
            <DataBrowser />
          ) : currentRoute === '/data-prep' ? (
            <DataPreparation />
          ) : currentRoute === '/settings' ? (
            <SettingsPage />
          ) : currentRoute === '/report-studio' ? (
            <ReportStudioPage />
          ) : null}
        </div>
      </div>
    </div>
  );
};

interface TopBarProps {
  title: string;
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}

const TopBar: React.FC<TopBarProps> = ({ title, sidebarCollapsed, onToggleSidebar }) => {
  const toggleLabel = sidebarCollapsed ? 'Expand navigation drawer' : 'Collapse navigation drawer';
  return (
    <header className="h-11 bg-white border-b border-slate-200/80 px-4 sm:px-6 flex items-center justify-between shrink-0">
      <div className="flex items-center gap-2 text-xs text-slate-400">
        <button
          type="button"
          onClick={onToggleSidebar}
          className="p-1 -ml-1 rounded hover:bg-slate-100 text-slate-500 hover:text-slate-800 transition-colors"
          title={toggleLabel}
          aria-label={toggleLabel}
        >
          <PanelLeft className="w-4 h-4" />
        </button>
        <span className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden">
          <img src="/Logo.svg" alt="" aria-hidden="true" className="h-full w-full scale-[2.5] object-contain" />
        </span>
        <span className="font-bold text-slate-800 tracking-tight text-sm">MarginTrace</span>
        <span>/</span>
        <span className="text-slate-600 font-medium">{title}</span>
      </div>
      <div className="text-xs text-slate-400 font-mono flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-emerald-500" />
        <span>HMLV Decision Platform</span>
      </div>
    </header>
  );
};

interface SidebarProps {
  currentRoute: string;
  collapsed: boolean;
  /** Run status shown next to the analysis entry. */
  analysisStatus: string;
  onNavigate: (path: string) => void;
}

const Sidebar: React.FC<SidebarProps> = ({ currentRoute, collapsed, analysisStatus, onNavigate }) => (
  <aside
    className={`${
      collapsed ? 'w-14' : 'w-14 sm:w-56'
    } bg-[#fafbfc] border-r border-slate-200/80 flex flex-col justify-between shrink-0 transition-all duration-200`}
  >
    <div className="p-3">
      {!collapsed && (
        <div className="hidden sm:block px-3 py-2 text-[10px] font-bold text-slate-400 uppercase tracking-wider">
          Navigation
        </div>
      )}
      <nav className="space-y-1">
        {ROUTES.map((route) => {
          const Icon = route.icon;
          const active = currentRoute === route.path;
          return (
            <button
              key={route.path}
              type="button"
              onClick={() => onNavigate(route.path)}
              title={route.label}
              aria-label={route.label}
              className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-xs transition-colors ${
                active ? route.activeClassName : (route.inactiveClassName ?? INACTIVE_NAV_CLASS)
              }`}
            >
              <Icon className={`w-4 h-4 ${route.iconClassName} shrink-0`} />
              <span className={collapsed ? 'hidden' : 'hidden sm:inline'}>{route.label}</span>
              {route.path === ANALYSIS_ROUTE && !collapsed && analysisStatus && (
                <span className="hidden sm:inline text-[10px] whitespace-nowrap" role="status">
                  {analysisStatus}
                </span>
              )}
            </button>
          );
        })}
      </nav>
    </div>

    <div
      className={`p-3 border-t border-slate-200/60 text-[11px] text-slate-400 text-center font-mono ${
        collapsed ? 'hidden' : 'hidden sm:block'
      }`}
    >
      HMLV Engine v4.8.2
    </div>
  </aside>
);

export default App;
