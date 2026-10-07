# AADisplay-101

[![AADisplay-101](https://img.shields.io/badge/AADisplay--101-Project-blue?logo=github)](https://github.com/Masterain98/AADisplay-101)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK](https://img.shields.io/badge/Android%20SDK-min%2031%20%C2%B7%20target%2037-brightgreen?logo=android)

> **Note:** This project is largely a **vibe programming** product — generated and iterated through extensive LLM-assisted development rather than traditional human-written code. Use with appropriate caution.

An Xposed / LSPosed module that mirrors almost any app onto the Android Auto screen via a VirtualDisplay. Migrated to **libxposed API 101** with support for Android 17 (API 37).

> [!IMPORTANT]
> AADisplay-101 has GitHub Immutable Releases feature enabled. All APK releases are exclusively built and published through GitHub Actions CI — they cannot be manually modified, ensuring software supply chain security.

## Release versioning

Run the **Release** workflow manually in GitHub Actions. Pushes do not publish releases.
CI generates versions using the Asia/Taipei (UTC+8) calendar date: `2026.10.7.0`
is the first version on October 7, followed by `2026.10.7.1`. The next day starts
at `2026.10.8.0`. Tags use the `v` prefix, for example `v2026.10.7.0`.

Published tags and release drafts both occupy sequence numbers. CI uses the
highest existing number plus one, serializes release runs, and generates a
strictly increasing Android `VERSION_CODE` with up to 1000 versions per day.
A failed build before a tag or draft is created does not consume a number;
an existing draft or tag is preserved and the next run chooses another number.
The APK version, filename, tag, and release title all use the generated version.

The manual workflow's `prerelease` option controls the GitHub prerelease flag
and defaults to `true`. CI passes the generated values to Gradle through
`AADISPLAY_VERSION_NAME` and `AADISPLAY_VERSION_CODE`; it does not commit or push
version updates. Local builds default to `1.0.0-dev` with version code `1`.
The internal version code is `days since 2000-01-01 * 1000 + daily index + 1`.

## Upstream

This project is forked from [`koalaauto/AADisplay-Beta`](https://github.com/koalaauto/AADisplay-Beta), which itself derives from [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay).

Renamed to **AADisplay-101** (package `com.aadisplay101.app`) to avoid conflicts with the original module on the same device.

## Requirements

- Android 12+ (SDK 31+; Android 10–11 unsupported)
- Rooted device with **LSPosed** (or a compatible Xposed environment supporting libxposed API 101)
- Working Android Auto (`com.google.android.projection.gearhead`)

> Some ROMs may be unstable or crash — use at your own risk.

## Usage

1. Enable the module in **LSPosed**, scoped to **System Framework** + **Android Auto**.
2. Set your launcher's package name in the module settings.
3. Optional: tune **DPI** and **resolution** for the car screen, or inject Android Auto **properties**.

Root is only used for user-configured shell commands — deny it if you don't need that.

## License

Same as upstream — see `LICENSE`.
