import { Camera, Eye, LayoutGrid, Monitor, Send, Settings, Smartphone, TabletSmartphone, Volume2 } from 'lucide-react';
import { useState } from 'react';
import { ActivityBar, StatusBar, TitleBar } from './components/Shell';
import type { ViewId } from './components/Shell';
import { DisplaysView } from './components/DisplaysView';
import { AudioView } from './components/AudioView';
import { DeckView } from './components/DeckView';
import { CameraView } from './components/CameraView';
import { PhoneView } from './components/PhoneView';
import { FilesView } from './components/FilesView';
import { DevicesView } from './components/DevicesView';
import { SettingsView } from './components/SettingsView';
import { useHostState } from './useHostState';

const SECTIONS = [
  { id: 'displays' as const, label: 'Displays', icon: Monitor },
  { id: 'audio' as const, label: 'Audio and media', icon: Volume2 },
  { id: 'deck' as const, label: 'Switchboard Deck', icon: LayoutGrid },
  { id: 'camera' as const, label: 'Camera', icon: Camera },
  { id: 'phone' as const, label: 'Phone screen', icon: TabletSmartphone },
  { id: 'files' as const, label: 'File transfers', icon: Send },
  { id: 'devices' as const, label: 'Paired devices', icon: Smartphone },
  { id: 'settings' as const, label: 'Settings', icon: Settings }
];

export const App = () => {
  const { state, error, refresh, setPaused, patch } = useHostState();
  const [view, setView] = useState<ViewId>('displays');

  const online = state?.devices.filter((d) => d.online).length ?? 0;
  const moving =
    state?.transfers.filter((t) => t.status === 'active' || t.status === 'pending').length ?? 0;

  return (
    <div className="flex h-full flex-col">
      <TitleBar subtitle={state ? `— ${state.host.hostName}` : ''} locked={state?.host.locked} />

      {/* A desktop being watched should always be able to see that it is,
          and to end it from here. */}
      {state?.screenViewers && state.screenViewers.length > 0 && (
        <div className="flex shrink-0 items-center gap-2 border-b border-warn/30 bg-warn/10 px-4 py-1.5 text-tiny text-warn">
          <Eye aria-hidden="true" className="h-3.5 w-3.5" />
          <span className="mr-auto">
            {state.screenViewers
              .map((v) => state.devices.find((d) => d.id === v.deviceId)?.name ?? 'A phone')
              .join(', ')}{' '}
            {state.screenViewers.length === 1 ? 'is' : 'are'} viewing this screen
          </span>
          <button
            type="button"
            onClick={() => window.switchboard.screen.stop().then(refresh)}
            className="rounded border border-warn/40 px-2 py-0.5 font-medium hover:bg-warn/20"
          >
            Stop sharing
          </button>
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        <ActivityBar
          items={SECTIONS.map((section) => {
            if (section.id === 'devices' && online > 0) return { ...section, badge: online };
            if (section.id === 'files' && moving > 0) return { ...section, badge: moving };
            return section;
          })}
          active={view}
          onSelect={setView}
        />

        {state ? (
          <>
            {view === 'displays' && (
              <DisplaysView state={state} patch={patch} setPaused={setPaused} refresh={refresh} />
            )}
            {view === 'audio' && <AudioView state={state} patch={patch} setPaused={setPaused} />}
            {view === 'deck' && <DeckView state={state} />}
            {view === 'camera' && <CameraView state={state} />}
            {view === 'phone' && <PhoneView state={state} />}
            {view === 'files' && <FilesView state={state} refresh={refresh} />}
            {view === 'devices' && <DevicesView state={state} refresh={refresh} />}
            {view === 'settings' && <SettingsView state={state} patch={patch} />}
          </>
        ) : (
          <div className="flex flex-1 items-center justify-center bg-canvas">
            <p className="text-tiny text-ink-faint">
              {error ? 'Waiting for the Switchboard daemon…' : 'Loading…'}
            </p>
          </div>
        )}
      </div>

      <StatusBar
        host={state ? `${state.host.hostName} · ${state.pairing.host}:${state.pairing.port}` : 'Connecting…'}
        devices={state?.devices.length ?? 0}
        error={error}
      />
    </div>
  );
};
