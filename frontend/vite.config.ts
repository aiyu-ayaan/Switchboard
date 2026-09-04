import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The renderer is loaded by Electron from disk in production, so assets must
// resolve relatively rather than from the server root.
export default defineConfig({
  root: 'src/renderer',
  base: './',
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5273, strictPort: true },
  build: {
    outDir: '../../dist/renderer',
    emptyOutDir: true
  }
});
