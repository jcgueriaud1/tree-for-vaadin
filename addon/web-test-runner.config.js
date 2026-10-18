import { esbuildPlugin } from '@web/dev-server-esbuild';
import { playwrightLauncher } from '@web/test-runner-playwright';

export default {
  files: 'src/test/frontend/**/*.test.ts',
  nodeResolve: true,
  plugins: [esbuildPlugin({ ts: true, tsconfig: 'tsconfig.json' })],
  browsers: [playwrightLauncher({ product: 'chromium' })],
};
