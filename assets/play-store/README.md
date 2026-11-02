# Google Play registration assets

Prepared for the first GalliPick registration. These files being in the repository does not mean the Play app or a public release has been created.

`ko-KR/app-icon.png` is an opaque 512×512 RGBA (32-bit PNG) icon. `ko-KR/feature-graphic.png` is a 1024×500 promotional graphic created with the built-in image generation tool using the existing GalliPick mascot as the brand reference.

The four numbered PNGs are actual native application captures, packaged as opaque 1350×2400 images without changing the interface or its proportions. They show onboarding, the filter dialog, dark settings, and a duplicate group. The duplicate capture contains controlled test files and placeholder thumbnails. No generated mockup is presented as a screenshot.

`ko-KR/manifest.json` records sources, packaging, and checksums. Feature and icon dimensions, RGB mode, and screenshot limits were checked against [Google Play preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151).

The Play app has now been created as GalliPick (`com.hebi.gallipick`), Korean and free. The policy/US-export declarations were accepted after explicit user approval. Nine of eleven setup items are complete. Korean store text is saved as a draft, while image upload and the IARC content rating remain incomplete. See [the registration checkpoint](../../docs/play-store/declarations.md) for saved settings and outstanding work. The internal-test release has version name and notes saved, with no AAB uploaded or release submitted.

The matching source AAB is version 0.1.6 (code 7), with user-provided signer fingerprint `3da9245ec543bba6348541e60346ebe8ed296bc0fb09eea4ae763138204d1bb1`. Its upload signature does not establish the Google Play distribution signing identity. The AAB itself and all signing credentials are intentionally excluded from this asset directory.
