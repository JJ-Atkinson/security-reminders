import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import terser from '@rollup/plugin-terser';

export default {
  input: 'resources/js/app.js',
  output: {
    file: 'resources/public/js/bundle.js',
    format: 'iife',
    sourcemap: true
  },
  onwarn(warning, warn) {
    // htmx uses eval internally — suppress this known warning
    if (warning.code === 'EVAL' && warning.id?.includes('htmx')) return;
    warn(warning);
  },
  plugins: [
    resolve(),
    commonjs(),
    terser()
  ]
};
