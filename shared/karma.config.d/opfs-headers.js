// SQLite WASM's OPFS backend needs a SharedArrayBuffer, which browsers only hand to
// cross-origin isolated pages. Required by WebOpfsPersistenceTest; harmless for the rest.
config.set({
    customHeaders: [
        { match: '.*', name: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
        { match: '.*', name: 'Cross-Origin-Embedder-Policy', value: 'require-corp' },
    ],
});
