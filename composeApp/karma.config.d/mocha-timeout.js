// Mocha's default is two seconds per test. The first test of a run shares that budget with
// loading the whole bundle — and this bundle is a Compose application, so as the app grew the
// first test began timing out while doing nothing but comparing two strings. The limit is per
// test, so a test that genuinely hangs still fails, just later.
config.set({
    client: {
        mocha: {
            timeout: 30000
        }
    }
});
