import React, { useEffect, useState } from 'react';
import { accelerationPreference, type AccelerationPreference, type ExecutionReport } from '../../analysisRun';
import { EmbeddingRuntimeSettings } from './EmbeddingRuntimeSettings';

interface BackendDevice { id: string; name: string; frontier: boolean; fgw: boolean }
interface Capabilities {
  cpu: ExecutionReport; cuda: ExecutionReport; opencl: ExecutionReport;
  devices: { cuda: BackendDevice[]; opencl: BackendDevice[] };
  stages: { cuda: { frontier: ExecutionReport; fgw: ExecutionReport }; opencl: { frontier: ExecutionReport; fgw: ExecutionReport } };
}

/** Saves caller preferences locally; capability evidence is supplied by the executing backend. */
export const AccelerationSettings: React.FC = () => {
  const [preferences, setPreferences] = useState<AccelerationPreference>(() => {
    const savedPreference = accelerationPreference();
    return savedPreference.isNvidia === false && savedPreference.deviceId && /^\d+$/.test(savedPreference.deviceId)
      ? { ...savedPreference, deviceId: `0:${savedPreference.deviceId}` } : savedPreference;
  });
  const [saved, setSaved] = useState(() => JSON.stringify(accelerationPreference()));
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/v1/analysis/capabilities', { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error(`Capabilities unavailable (${response.status})`);
      return response.json() as Promise<Capabilities>;
    }).then(setCapabilities).catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : 'Capabilities unavailable'); });
    return () => controller.abort();
  }, []);
  const change = (values: Partial<AccelerationPreference>) => { setPreferences(previous => ({ ...previous, ...values })); setNotice(null); };
  const save = () => {
    if (preferences.useGpu && preferences.isNvidia == null) { setNotice('Select the backend GPU vendor.'); return; }
    try { localStorage.setItem('margintrace.acceleration', JSON.stringify(preferences)); setSaved(JSON.stringify(preferences)); setNotice('Saved in this browser for the next analysis run.'); }
    catch { setNotice('Unable to save preferences in this browser.'); }
  };
  const family = !preferences.useGpu ? 'cpu' : preferences.isNvidia === true ? 'cuda' : preferences.isNvidia === false ? 'opencl' : null;
  const capability = family ? capabilities?.[family] : null;
  const devices = family === 'cuda' || family === 'opencl' ? capabilities?.devices?.[family] || [] : [];
  const stages = family === 'cuda' || family === 'opencl' ? capabilities?.stages?.[family] : null;
  return <details className="border rounded-lg bg-white settings-section"><summary className="cursor-pointer p-5 text-sm font-semibold">Calculation and acceleration <span className="ml-2 text-xs font-normal text-slate-600">{!preferences.useGpu ? 'CPU' : preferences.isNvidia == null ? 'Vendor required' : preferences.isNvidia ? 'CUDA requested' : 'OpenCL requested'}{JSON.stringify(preferences) !== saved ? ' · Unsaved' : ''}</span></summary>
    <div className="p-5 pt-0 space-y-4 text-xs settings-content">
      <label className="flex gap-2 items-center"><input type="checkbox" role="switch" checked={preferences.useGpu} onChange={e => change({ useGpu: e.target.checked })} /> Use GPU acceleration</label>
      {preferences.useGpu && <><label className="block">Backend GPU vendor <select aria-label="Backend GPU vendor" className="border rounded ml-2 p-1" value={preferences.isNvidia == null ? '' : preferences.isNvidia ? 'nvidia' : 'other'} onChange={e => change({ isNvidia: e.target.value === '' ? null : e.target.value === 'nvidia', deviceId: null })}><option value="">Select vendor</option><option value="nvidia">NVIDIA / CUDA</option><option value="other">Non-NVIDIA / OpenCL</option></select></label><label className="block">Backend GPU device <select aria-label="Backend GPU device" className="border rounded ml-2 p-1" value={preferences.deviceId || ''} onChange={e => change({ deviceId: e.target.value || null })}>
        <option value="">Automatic within selected runtime</option>
        {preferences.deviceId && !devices.some(d => d.id === preferences.deviceId) && <option value={preferences.deviceId}>Saved device {preferences.deviceId} · unavailable</option>}
        {devices.map(d => <option key={d.id} value={d.id}>{d.name} ({d.id}){!d.fgw ? ' · FGW unavailable' : ''}</option>)}
      </select></label><label className="flex gap-2 items-center"><input type="checkbox" checked={preferences.allowCpuFallback} onChange={e => change({ allowCpuFallback: e.target.checked })} /> Allow explicit CPU fallback when this GPU engine is unavailable</label></>}
      {error ? <p className="text-[#A64242]" role="alert">{error}</p> : capability ? <p>Backend capability: {capability.requested} · {capability.status}{capability.fallbackReason ? ` · ${capability.fallbackReason}` : ''}</p> : <p>{capabilities ? 'Choose a vendor to inspect capability.' : 'Loading backend capabilities…'}</p>}
      {stages && <p>Frontier: {stages.frontier.status} · FGW: {stages.fgw.status}</p>}
      <p className="text-slate-600">Devices belong to the backend server. Automatic selection stays within the selected runtime.</p>
      <p className="text-slate-600">Preferences apply to frontier and FGW. Model inference has its own runtime below. Saving a preference does not establish device availability.</p>
      <button className="border rounded px-3 py-2" disabled={preferences.useGpu && preferences.isNvidia == null} onClick={save}>Save calculation preferences</button>
      {notice && <p role="status">{notice}</p>}
      <EmbeddingRuntimeSettings />
    </div>
  </details>;
};
