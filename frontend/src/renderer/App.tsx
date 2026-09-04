import { Monitor, Smartphone, Volume2 } from 'lucide-react';
import { useState } from 'react';
import { ActivityBar, StatusBar, TitleBar } from './components/Shell';
import type { ViewId } from './components/Shell';
import { DisplaysView } from './components/DisplaysView';
import { AudioView } from './components/AudioView';
import { DevicesView } from './components/DevicesView';
import { useHostState } from './useHostState';

const SECTIONS = [
  { id: 'displays' as const, label: 'Displays', icon: Monitor },
  { id: 'audio' as const, label: 'Audio and media', icon: Volume2 },
  { id: 'devices' as const, label: 'Paired devices', icon: Smartphone }
];

export const App = () => {
  const { state, error, refresh, setPaused, patch } = useHostState();
  const [view, setView] = useState<ViewId>('displays');

  const online = state?.devices.filter((d) => d.online).length ?? 0;

  return (
    <div className="flex h-full flex-col">
      <TitleBar subtitle={state ? `— ${state.host.hostName}` : ''} />

      <div className="flex min-h-0 flex-1">
        <ActivityBar
          items={SECTIONS.map((section) =>
            section.id === 'devices' && online > 0 ? { ...section, badge: online } : section
          )}
          active={view}
          onSelect={setView}
        />

        {state ? (
          <>
            {view === 'displays' && (
              <DisplaysView state={state} patch={patch} setPaused={setPaused} refresh={refresh} />
            )}
            {view === 'audio' && <AudioView state={state} patch={patch} setPaused={setPaused} />}
            {view === 'devices' && <DevicesView state={state} refresh={refresh} />}
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
