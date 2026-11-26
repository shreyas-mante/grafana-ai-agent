import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The UI is bundled into the Spring Boot jar and served same-origin (no CORS, strict cookies).
// No source maps are published, and console/debugger calls are stripped from production builds.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false,
    minify: 'esbuild',
    target: 'es2020',
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[hash].js',
        chunkFileNames: 'assets/[hash].js',
        assetFileNames: 'assets/[hash][extname]',
      },
    },
  },
  esbuild: { drop: ['console', 'debugger'], legalComments: 'none' },
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
});
