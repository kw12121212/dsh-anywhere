import { resolve } from 'node:path'
import { defineConfig } from 'electron-vite'
import { readPackageVersion } from './scripts/package-version.mts'

export default defineConfig({
  main: {
    define: {
      // 版本号构建期注入（packaging/package-version-single-source）：唯一登记处是
      // package.json 的 version，define 与 electron-builder 元数据读取同一文件。
      __APP_VERSION__: JSON.stringify(readPackageVersion(resolve(__dirname, 'package.json')))
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
