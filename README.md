# Ymir Android

Ymir Android is a Sega Saturn emulator for Android based on the GPL-licensed
Ymir emulator core. This fork adds a touch-first Android interface, game library
management, per-game bezels, IGDB metadata, and Google Play release automation.

The project does not include Sega BIOS files, Sega Saturn games, copyrighted
disc images, or other commercial game content. Users must provide their own
legally obtained Saturn BIOS and game images.

## Current Android Features

- Sega Saturn emulation using the Ymir native core.
- Game library screen with cover cards instead of a single file picker.
- Cached IGDB metadata and cover art for matched Saturn titles.
- Long-press game details with metadata, images, manual IGDB rematch, and manual
  bezel selection.
- Bezel support from a local Ymir folder, including A-Z organization and search.
- Software rendering with experimental Vulkan work isolated behind the renderer
  toggle.
- CRT and bezel toggles in the main UI.
- Debug information behind a sidebar action instead of being shown inline.
- Android App Bundle release path for Google Play.

## Game Library

The Android app scans the configured game library folder and builds a local
library of Saturn game cards. Metadata is looked up only for new or unmatched
titles and then cached locally so the app does not query IGDB every time the
library is opened.

Supported Saturn image formats depend on the underlying Ymir core and include
common disc image layouts such as CHD, BIN/CUE, ISO, IMG/CCD, and MDF/MDS.

## Bezels

Bezels are read from the local Ymir data folder on the device. The intended
layout is an A-Z folder structure so browsing remains responsive with a large
bezel set. The app attempts to match bezels to games automatically using the
library title and file-derived identifiers. If automatic matching fails, open the
game details view and select the matching bezel manually.

Third-party bezel artwork is not part of the emulator license unless the artwork
owner has licensed it that way. If a release bundles any bezel artwork, that
artwork needs its own license review and notice.

## IGDB Metadata

IGDB is used for Saturn-only metadata matching. The Android app stores matched
game details and image URLs locally after a successful match, then only queries
IGDB for new or rematched titles.

IGDB client credentials must be provided as private release secrets and must not
be committed to the repository.

## Privacy

Ymir Android stores library metadata, IGDB matches, selected bezels, renderer
preferences, and user settings on the device. The app does not need to upload
the user's game library. Network access is used for IGDB metadata and artwork
requests when metadata matching is enabled.

## Legal Content Policy

Ymir Android is an emulator. The Play Store package must not ship with:

- Sega Saturn BIOS ROMs.
- Sega Saturn game ROMs, disc images, or extracted game files.
- Sega trademarks used in a way that suggests endorsement.
- Third-party artwork, covers, screenshots, bezels, or metadata outside the
  permissions granted by their owners or APIs.

Users are responsible for supplying their own legally obtained BIOS and games.

## License

The emulator source code is distributed under the GNU General Public License,
version 3. See [LICENSE](LICENSE) for the full license text.

GPLv3 permits charging money for copies of GPL software, including copies
distributed through an app store, provided the distributor follows the GPL
requirements. That means the Google Play listing and app distribution must keep
copyright notices intact, include the GPL license text, provide the complete
corresponding source for the exact release, and preserve the user's GPL rights to
copy, inspect, modify, and redistribute the software.

More detail for Play Store distribution is in
[PLAY_STORE_LICENSING.md](PLAY_STORE_LICENSING.md).

## Release Automation

The repository includes a manually triggered GitHub Actions workflow named
`Google Play Release`. It creates a signed Android App Bundle with version code
`1`, uploads the AAB as a workflow artifact, and publishes it to the selected
Google Play track using private repository secrets.

Release secret names and signing expectations are documented in
[GOOGLE_PLAY_RELEASE.md](GOOGLE_PLAY_RELEASE.md).
