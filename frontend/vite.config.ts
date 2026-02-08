import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Forward /api calls to Spring Boot app on 8080
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Forward /actuator calls to the management port on 8081
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})