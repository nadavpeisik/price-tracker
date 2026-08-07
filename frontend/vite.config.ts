/// <reference types="vitest/config" />
import path from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Prod mock-gate (#144): a production build must never contain mock data.
  // `vite build` runs with mode === 'production' by default; if someone forces
  // mock mode into a production build, fail the build outright instead of
  // shipping a demo artifact. Resolve VITE_USE_MOCK via loadEnv so the gate
  // catches it from ANY source — a shell export AND an .env/.env.production
  // file (Vite only surfaces the latter in import.meta.env, not process.env,
  // so a bare process.env check would miss a file-sourced value).
  const env = loadEnv(mode, process.cwd(), '')
  if (mode === 'production' && env.VITE_USE_MOCK === 'true') {
    throw new Error(
      'VITE_USE_MOCK=true is not allowed for production builds — mock data must never ship. ' +
        'Unset VITE_USE_MOCK or build in a non-production mode.',
    )
  }

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      proxy: {
        // Dev-only reverse proxy: the browser calls the Vite origin, Vite
        // forwards /api/* to the Spring backend — no CORS involved.
        '/api': 'http://localhost:8080',
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  }
})
