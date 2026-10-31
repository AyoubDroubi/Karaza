# flexible-adapter (vendored)

Source of [arkon/FlexibleAdapter](https://github.com/arkon/FlexibleAdapter) at commit
`c80135339bcff5f7f8c2c2380329dfc155b26232` ("Fix missing Lifecycle dependency"), the exact
version the app previously pulled from JitPack as `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533`.

Vendored because JitPack's build of that commit fails (a build plugin it needs,
`nu.studer:java-ordered-properties:1.0.1`, is no longer published), so the artifact can no
longer be downloaded. Changes from upstream, both required by current Android Gradle Plugin versions:

- the manifest's `package` attribute is replaced by the Gradle `namespace`;
- `SelectableAdapter` logs the fixed string `"5.1.0"` instead of `BuildConfig.VERSION_NAME`,
  which AGP no longer generates for library modules.

Licensed under the Apache License 2.0 — see `LICENSE`.
