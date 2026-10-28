import { resolve } from 'node:path'
import { defineConfig } from 'electron-vite'

export default defineConfig({
  main: {
    define: {
      // 版本号构建期注入（设计决策 version-injection）：默认 dev，发布构建用
      // APP_VERSION 环境变量注入，由 electron-release 计划变更接线。
      __APP_VERSION__: JSON.stringify(process.env['APP_VERSION'] ?? 'dev')
    },
    build: {
      rollupOptions: {
        input: { index: resolve(__dirname, 'src/main/index.ts') }
      }
    }
  },
  preload: {
    build: {
      rollupOptions: {
        input: { index: resolve(__dirname, 'src/preload/index.ts') }
      }
    }
  },
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    build: {
      rollupOptions: {
        input: { index: resolve(__dirname, 'src/renderer/index.html') }
      }
    }
  }
})
