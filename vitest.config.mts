import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'scripts/**/*.test.mts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts', 'scripts/**/*.mts'],
      // 入口文件只做装配，按 Go 版排除 main.go 的惯例排除。
      exclude: ['src/main/index.ts', 'scripts/flip-fuses.mts', '**/*.test.ts', '**/*.test.mts'],
      reporter: ['text'],
      thresholds: { lines: 80 }
    }
  }
})
