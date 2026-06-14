# Play Store Licensing

This document records why a paid Google Play release of Ymir Android can be
distributed when the release is handled correctly. It is a project compliance
note, not legal advice.

## Summary

Ymir Android is distributed under the GNU General Public License, version 3
(GPLv3). GPLv3 allows commercial distribution and allows charging for copies.
The key condition is that buyers receive the same software freedoms: access to
the corresponding source code, the license text, copyright notices, and the
right to copy, modify, and redistribute the GPL-covered work.

The FSF GPL FAQ states that selling GPL-covered programs is allowed and that the
right to sell copies is part of free software. The GPLv3 text also says that
copies may be distributed for a fee, as long as recipients can get source code
and receive the license terms:

- https://www.gnu.org/licenses/gpl-faq.html#DoesTheGPLAllowMoney
- https://www.gnu.org/licenses/gpl-3.0.en.html
- https://www.gnu.org/philosophy/selling.en.html

## Conditions For A Play Store Release

The Play Store release is acceptable only if all of these conditions are met:

- The app remains licensed as GPLv3.
- The complete corresponding source for the exact Play Store build is available.
- The source offer is clear in the Play listing or another durable location
  linked from the listing.
- The GPLv3 license text is included in the repository and made available to
  users.
- Copyright notices from upstream Ymir and third-party components are preserved.
- No extra terms are imposed that remove the user's GPL rights.
- The app does not include BIOS files, game disc images, ROMs, or copyrighted
  game assets.
- Any bundled third-party artwork, fonts, metadata, or bezel packs have separate
  permission and notices.

## Why Charging Is Allowed

GPL software is free as in user freedom, not necessarily zero price. Charging on
Google Play is therefore allowed, but the price cannot be used to stop recipients
from exercising GPL rights. A user who buys the app may still copy and
redistribute the GPL-covered code under GPLv3.

## Source Code Requirement

Every Play Store binary release should correspond to a public source state. Use
a release tag and keep the source for that tag available. The source should
include the Android project, native source, Gradle files, CMake files, scripts,
and any other files needed to build and install the same app version.

Do not publish private release material as source code. Google Play signing keys,
keystores, service account JSON files, and API secrets are credentials, not
source code.

## App Signing

GPLv3 does not generally require publishing private signing keys. The FSF GPL
FAQ says private signing keys are only required in the specific User Product
case where hardware refuses to run modified GPL software without that signature.
An Android phone that allows sideloaded builds does not require publishing the
Play signing key.

Google Play App Signing and upload keys should remain private.

## Third-Party Component Notices

The source tree includes third-party components under permissive or GPL-compatible
licenses. Current notable components include:

- `vendor/fmt`: MIT-style license.
- `vendor/mio`: MIT license.
- `vendor/imgui`: MIT license.
- `vendor/libchdr`: BSD-style license.
- `vendor/lz4`: BSD 2-Clause for the library directory; GPL-2.0-or-later for
  other files in that upstream repository.
- `vendor/xxHash`: BSD 2-Clause license.
- `vendor/concurrentqueue`: Simplified BSD or Boost Software License.

Before each production release, re-run a license audit if vendor code changes or
new libraries are added.

## BezelProject And Artwork

The BezelProject artwork is separate from Ymir Android code. Do not assume the
GPL for Ymir Android covers third-party bezel artwork. If bezel packs are copied
into a release artifact, include only artwork that has been reviewed for
redistribution and commercial app-store use. The safer default is to let users
copy bezels into their local Ymir folder themselves.

## IGDB

IGDB metadata and images are API-provided third-party content. Keep the IGDB API
credentials private, cache only what the IGDB terms allow, and do not present
IGDB content as project-owned artwork.

## Play Listing Compliance

The Play Store listing should state:

- The app is a Sega Saturn emulator.
- No BIOS files or games are included.
- Users must provide their own legally obtained BIOS and game images.
- Source code is available at the public repository URL for the exact release.
- The app is GPLv3 licensed.

Avoid wording or graphics that imply Sega, IGDB, or BezelProject endorsement.
