import React from 'react';

export const App: React.FC = () => {
  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-slate-900 text-white">
      <h1 className="text-3xl font-bold">Switchboard Desktop</h1>
      <p className="mt-2 text-slate-400">Scan QR Code or enter pairing code from your mobile app.</p>
    </div>
  );
};
