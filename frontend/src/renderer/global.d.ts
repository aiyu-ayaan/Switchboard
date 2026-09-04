import type { SwitchboardBridge } from '../shared/types';

declare global {
  interface Window {
    /** Injected by the preload bridge; see src/preload/index.ts. */
    switchboard: SwitchboardBridge;
  }
}

export {};
