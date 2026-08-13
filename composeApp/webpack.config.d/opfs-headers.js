// SQLite WASM reaches OPFS through a SharedArrayBuffer, which the browser only hands out to
// cross-origin isolated pages. Whatever serves the production build needs these headers too.
;(function(config) {
  config.devServer = config.devServer || {};
  config.devServer.headers = [
      { key: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
      { key: 'Cross-Origin-Embedder-Policy', value: 'require-corp' }
  ];
})(config);
