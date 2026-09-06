import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { SendPicker } from './components/SendPicker';
import './styles.css';

// The Explorer send picker shares this bundle with the main shell and is told
// apart by the hash the main process loads it with. A second Vite entry point
// would be a second build target for one small window.
const isSendPicker = window.location.hash === '#send';

const rootElement = document.getElementById('root');
if (rootElement) {
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>{isSendPicker ? <SendPicker /> : <App />}</React.StrictMode>
  );
}
