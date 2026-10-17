# Client dependency audit (`npm audit`)

**Date:** 2026-10-17 · **Branch:** `phase-6-hardening` · **Toolchain:** Node 24.3.0, npm 11.4.2, Expo SDK 58, React Native 0.88.0

## Summary

| | Total | High | Moderate | Critical |
|---|---|---|---|---|
| Before, `npm audit` | 70 | 53 | 17 | 0 |
| Before, `npm audit --omit=dev` | 67 | 50 | 17 | 0 |
| **After, `npm audit`** | **63** | **33** | **30** | 0 |
| **After, `npm audit --omit=dev`** | **55** | **24** | **31** | 0 |

npm reports one row per *vulnerable package*, including every package that only depends on a vulnerable one. All 63 rows come from
**five actual advisories** in five leaf packages. None of the five is in the shipped app (web export or native runtime); all are
build/test tooling running on a developer or CI/EAS machine against our own config and source files.

The moderate count went up (17 to 30) because the jest 30 packages now reach the vulnerable code only through the moderate
`sprintf-js` chain instead of the high `micromatch`/`braces` chain. They moved from high to moderate; nothing new was added.

## What changed

| Package | From | To | Why |
|---|---|---|---|
| `expo` | ~58.0.13 | ~58.0.14 | SDK 58 patch (`npx expo install --check`) |
| `expo-router` | ~58.0.22 | ~58.0.23 | SDK 58 patch (`npx expo install --check`) |
| `jest` (dev) | ~29.7.0 | ~30.5.2 | Version SDK 58 expects. `jest-expo@58` already ran on jest 30 internals (`babel-jest`/`jest-environment-node` 30.5.2) and `jest-watch-typeahead@3` needs `jest@^30`, so 29 was an invalid peer. Drops the jest-29 `micromatch` chains and makes `expo-doctor` pass (it failed before). |
| `@types/jest` (dev) | 29.5.14 | 30.0.0 | Version SDK 58 expects, to match jest 30 |

The jest change is a major version, but it is the version Expo SDK 58 requires, not an upgrade beyond the SDK.
**No `overrides` were added.** For three advisories no fixed version has been published. For the other two the fix is a new major
version that the requesting package's semver range does not allow (see below).

## Remaining advisories

| Advisory | Package (installed) | Severity | Dependency path(s) | Runtime or dev-only | Why not fixed | Risk |
|---|---|---|---|---|---|---|
| [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm): stack-exhaustion DoS from deeply nested brace patterns | `braces@3.0.3` (via `micromatch@4.0.8`) | high | `tailwindcss@3.4` → chokidar / micromatch / fast-glob; `expo` → `@expo/metro` → `metro-file-map`, `metro` (also via `react-native` → `@react-native/community-cli-plugin`); `jest-expo` → `@react-native/jest-preset` → `babel-jest@29` → `@jest/transform@29`; `jest@30` → `@jest/transform` | Dev/build only (Metro bundler, Tailwind compiler, Jest). Not in the bundle. | Affects `<=3.0.3`, and 3.0.3 is the latest release. No fix is available, and `micromatch@4.0.8` is also the latest. | **Low.** Only glob patterns from our own config (`tailwind.config.js` content globs, Metro/Jest config) reach `braces`. No untrusted input does. Worst case is a crashed local build. |
| [GHSA-86w9-cpqp-85rv](https://github.com/advisories/GHSA-86w9-cpqp-85rv): RSA PKCS#1 v1.5 signature-verification forgery | `node-forge@1.4.0` | high | `expo` → `@expo/cli` (and → `@expo/code-signing-certificates`) | Dev CLI only. Not in the bundle. | Affects `<=1.4.0`, and 1.4.0 is the latest release. No fix is available. | **Low.** `@expo/cli` uses node-forge to read local Keychain certificates for `expo run:ios` and, through `@expo/code-signing-certificates`, to sign expo-updates manifests. We don't install `expo-updates` or set a `codeSigningCertificate`, so no signature verification of untrusted data happens. |
| [GHSA-hp3w-g68c-fv3c](https://github.com/advisories/GHSA-hp3w-g68c-fv3c): DoS through unbounded precision specifiers | `sprintf-js@1.0.3` | moderate | `jest-expo` / `jest` → `babel-jest@30` → `babel-plugin-istanbul@8` → `@istanbuljs/load-nyc-config` → `js-yaml@3` → `argparse@1` → `sprintf-js` | Test/coverage tooling only | Affects `<=1.1.3`, and 1.1.3 is the latest release. No fix is available. (A newer `js-yaml` would drop it, but `load-nyc-config` pins `js-yaml@^3`.) | **Negligible.** argparse formats its own help strings. Our format strings never reach it. |
| [GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq): missing buffer bounds check in v3/v5/v6 when `buf` is passed | `uuid@7.0.3` | moderate | `expo` / `expo-splash-screen` / `@expo/prebuild-config` → `@expo/config-plugins` → `xcode@3.0.1` → `uuid` | Prebuild/config-plugin time only. Not in the bundle. | Fixed in `11.1.1`, but `xcode@3.0.1` (latest) requires `uuid@^7.0.3`, so the fix is a major-version override outside the requester's range. | **Negligible / not reachable.** `xcode` only calls `uuid.v4()` with no `buf` argument (`pbxProject.js`). The vulnerable v3/v5/v6 + `buf` code path is never used. |
| [GHSA-rj75-hqrm-r3gf](https://github.com/advisories/GHSA-rj75-hqrm-r3gf): quadratic CPU use in flat selector parsing | `postcss-selector-parser@6.1.4` | moderate | `tailwindcss@3.4.19` (direct and via `postcss-nested@6.2.0`); `nativewind` → `react-native-css-interop` → `tailwindcss` | Build-time only (Tailwind compiles CSS during Metro bundling). The bundle contains the nativewind / css-interop runtime, but not tailwindcss or the parser. | Fixed in `7.1.6`, but tailwindcss 3 and postcss-nested 6 require `^6.x`. The fix needs a major-version override, or Tailwind 4, which NativeWind 4 doesn't support. | **Low.** It only parses selectors from our own `global.css` and the Tailwind config. No user-supplied CSS. |

### How "runtime vs dev-only" was established
`npm audit --omit=dev` still counts most of these packages (55), because `expo` lists `@expo/cli`/Metro/config-plugins as regular
dependencies and `expo-router` has a peer dependency on `@testing-library/react-native` → `jest`. To check what actually ships, we ran
`npx expo export -p web --source-maps` and listed every `node_modules` source in the maps. Of the flagged packages, the bundle contains
only `@expo/cli/build/metro-require/require.js` (Metro's module-loader shim) and the `nativewind` / `react-native-css-interop` runtime.
Their advisories come in only through build-time dependencies (`node-forge`, `tailwindcss`). `braces`, `micromatch`, `node-forge`,
`sprintf-js`, `uuid`, `postcss-selector-parser`, `xcode`, `tailwindcss` and every jest package are absent. Native builds use the same
Metro module graph.

### Fix options considered and rejected
- `npm audit fix --force`: proposes `expo@44`, `react-native@0.72`, `jest-expo@56`, `nativewind@2`, `tailwindcss@4`, which are all downgrades or SDK breaks. Not applied.
- Overriding `uuid` to `^11.1.1` for `xcode` would probably work (`xcode` only uses `v4()`, which uuid 11 still exports as CommonJS), but it is outside `xcode`'s `^7` range. We can revisit this if a zero-moderate goal is set. If applied, run `npx expo prebuild --no-install` in a scratch copy to confirm iOS project generation.
- Overriding `postcss-selector-parser` to `^7.1.6` for tailwind 3 is outside the declared range and untested by Tailwind. Not applied.

## Re-check procedure
```bash
cd client
npx expo install --check                     # SDK-aligned patch bumps first
npm audit                                    # full count
npm audit --omit=dev                         # "production" count (still includes Expo CLI/Metro, see above)
npm audit --json > /tmp/audit.json           # per-package detail: via / isDirect / fixAvailable
npm ls <pkg> --all                           # dependency path for any flagged package
npm view <pkg> versions                      # has a fixed release appeared?
# after any change, run the full verify:
npm ci && npm run typecheck && npm run lint && npm test -- --watchAll=false \
  && npx expo export -p web && rm -rf dist && npx expo-doctor
```
Re-check when moving Expo SDK patch or minor versions, before each release, and when any of the five advisories above gets a fixed release.
The most likely fixes are a `braces`/`micromatch` release, a `node-forge` 1.4.x patch, or `xcode` moving to a newer `uuid`.

## CI gate (added: tools/npm-audit-gate.cjs, run in the client CI job)
A plain `npm audit --omit=dev --audit-level=high` gate **would fail today**: it exits 1 because of `braces` (high) and `node-forge`
(high), which reach the production tree through `expo` → `@expo/cli`/Metro. `--audit-level=critical` would pass (0 critical), but it
gates very little. Suggested options:

1. **Allowlist gate (recommended).** Fail on any high or critical advisory whose GHSA ID is not in the accepted list in this document:
   ```bash
   npm audit --omit=dev --json | node -e '
     const ok = new Set(["GHSA-vfj7-8cjw-p6xm","GHSA-86w9-cpqp-85rv"]);
     const v = Object.values(JSON.parse(require("fs").readFileSync(0)).vulnerabilities);
     const bad = v.flatMap(p => p.via).filter(x => typeof x === "object"
       && ["high","critical"].includes(x.severity) && !ok.has(x.url.split("/").pop()));
     if (bad.length) { console.error(bad); process.exit(1); }'
   ```
   (Tools such as `audit-ci` with an allowlist do the same.) Every allowlist entry must have a row in the table above.
2. **Critical-only hard gate** (`npm audit --omit=dev --audit-level=critical`) plus a non-blocking full `npm audit` report step.

## Appendix: every package `npm audit` flags (after)
| Package | Severity | Direct? | Root advisory(ies) | Counted by `--omit=dev`? |
|---|---|---|---|---|
| `@expo/cli` | high | no | braces, node-forge, uuid | yes |
| `@expo/code-signing-certificates` | high | no | node-forge | yes |
| `@expo/config` | moderate | no | uuid | yes |
| `@expo/config-plugins` | moderate | no | uuid | yes |
| `@expo/inline-modules` | moderate | no | uuid | yes |
| `@expo/local-build-cache-provider` | moderate | no | uuid | yes |
| `@expo/metro` | high | no | braces | yes |
| `@expo/metro-config` | high | no | braces, uuid | yes |
| `@expo/prebuild-config` | moderate | no | uuid | yes |
| `@istanbuljs/load-nyc-config` | moderate | no | sprintf-js | yes |
| `@jest/core` | moderate | no | braces, sprintf-js | yes |
| `@jest/environment` | high | no | braces | no |
| `@jest/expect` | moderate | no | braces, sprintf-js | yes |
| `@jest/fake-timers` | high | no | braces | no |
| `@jest/globals` | moderate | no | braces, sprintf-js | yes |
| `@jest/reporters` | moderate | no | braces, sprintf-js | yes |
| `@jest/transform` | high | no | braces, sprintf-js | yes |
| `@react-native/community-cli-plugin` | high | no | braces | yes |
| `@react-native/jest-preset` | high | no | braces, sprintf-js | no |
| `@react-native/metro-config` | high | no |  | yes |
| `@react-native/virtualized-lists` | high | no | braces | yes |
| `@testing-library/react-native` | high | yes | braces, sprintf-js | yes |
| `argparse` | moderate | no | sprintf-js | yes |
| `babel-jest` | high | no | braces, sprintf-js | yes |
| `babel-plugin-istanbul` | moderate | no | sprintf-js | yes |
| `braces` | high | no | braces | yes |
| `chokidar` | high | no | braces | yes |
| `expo` | high | yes | braces, node-forge, uuid | yes |
| `expo-modules-core` | high | no | braces | yes |
| `expo-router` | high | yes | braces, sprintf-js | yes |
| `expo-splash-screen` | moderate | yes | uuid | yes |
| `fast-glob` | high | no | braces | yes |
| `jest` | moderate | yes | braces, sprintf-js | yes |
| `jest-circus` | moderate | no | braces, sprintf-js | yes |
| `jest-cli` | moderate | no | braces, sprintf-js | yes |
| `jest-config` | moderate | no | braces, sprintf-js | yes |
| `jest-environment-node` | high | no | braces | no |
| `jest-expo` | high | yes | braces, sprintf-js | no |
| `jest-haste-map` | high | no | braces | no |
| `jest-message-util` | high | no | braces | no |
| `jest-resolve-dependencies` | moderate | no | braces, sprintf-js | yes |
| `jest-runner` | moderate | no | braces, sprintf-js | yes |
| `jest-runtime` | moderate | no | braces, sprintf-js | yes |
| `jest-snapshot` | moderate | no | braces, sprintf-js | yes |
| `jest-watch-typeahead` | moderate | no | braces, sprintf-js | no |
| `js-yaml` | moderate | no | sprintf-js | yes |
| `metro` | high | no | braces | yes |
| `metro-config` | high | no |  | yes |
| `metro-file-map` | high | no | braces | yes |
| `metro-transform-worker` | high | no |  | yes |
| `micromatch` | high | no | braces | yes |
| `nativewind` | moderate | yes | braces, postcss-selector-parser | yes |
| `node-forge` | high | no | node-forge | yes |
| `postcss-nested` | moderate | no | postcss-selector-parser | yes |
| `postcss-selector-parser` | moderate | no | postcss-selector-parser | yes |
| `react-native` | high | yes | braces | yes |
| `react-native-css-interop` | moderate | no | braces, postcss-selector-parser | yes |
| `react-native-reanimated` | high | yes | braces | yes |
| `react-native-worklets` | high | yes | braces | yes |
| `sprintf-js` | moderate | no | sprintf-js | yes |
| `tailwindcss` | high | yes | braces, postcss-selector-parser | yes |
| `uuid` | moderate | no | uuid | yes |
| `xcode` | moderate | no | uuid | yes |
