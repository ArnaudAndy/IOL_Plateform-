import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Charge .env / .env.local pour que VITE_API_PROXY_TARGET soit réellement pris en compte.
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost'

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 5173,
      host: true,
      // Proxy /api vers le backend IOL ETL (api-core). Cible pilotée par VITE_API_PROXY_TARGET.
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
        '/interop': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
