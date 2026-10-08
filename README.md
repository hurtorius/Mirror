# Mirror

Mirror that displays any media from local devices or access to internet like a browser in your world.

For **Minecraft Java 1.21.11**, **Fabric Loader 0.19.5+**, and **Java 21**.

## Install

Download the current mod from [Modrinth](https://modrinth.com/mod/mirror-hurtorius).
Put the JAR in your instance's `mods` folder. Fabric API and Polymer are bundled.
For multiplayer, install the same Mirror version on the server and on every
client that wants to see screens. Clients without Mirror can join but cannot
view the screens.

Windows web browsing requires **Google Chrome**. Mirror uses its own local browser
profile and displays the browser inside Minecraft. It does not use your normal
Chrome profile. Windows x64 is the tested platform; Linux/macOS media and browser
paths are included but have not been verified on those platforms.

## Use

1. As an operator in Creative mode, take the **Mirror Initiator** from the Mirror tab.
2. Place it and right-click to open its controls. New screens start off.
3. Choose Web, Media, Text, Blank, Share, or Draw. Configure the content, then use
   **Screen: On** and **Done**. Done saves; Esc discards unsaved editor settings.

All editing starts at the Initiator. There are no Mirror commands. Viewers can
open their personal display settings from the block. F9 hides/mutes screens for
you; F10 stops your outgoing stream.

- **Web:** browse with keyboard and mouse, page sound, persistent sessions, and
  30/60 FPS modes. The browser viewport can follow the screen's aspect ratio or
  use custom dimensions. Account opens a normal Chrome window for private sign-in
  using Mirror's profile; publishing stops during account setup.
- **Media:** import pictures, video, music, PDFs, and subtitles; arrange playlists
  and control playback. Imports are stored with the world.
- **Share:** request a player's consent to share a selected display or window.
  Sound is a separate choice. Changing the audience revokes consent.
- **Draw:** a saved whiteboard with pen, marker, eraser, shapes, fill, text,
  selection, undo/redo, and zoom/pan.
- **Layout:** type world-center coordinates, set size/shape/facing, and use the
  screen-centered preview. Front-only screens have a black back; both-readable
  and both-mirrored modes are also available.
- **Display and access:** brightness (default 1), volume, glow, opacity, viewing
  range, selected audiences, sharing permissions, and site rules. Browser errors
  and other reports are available inside the editor.

Confirmed imports and live drawing are saved independently of the editor draft.
Website sign-in checks, DRM, and cross-origin restrictions still apply; Mirror
cannot guarantee playback on every site. GIF imports are still images. Export
Office documents to PDF first. Live desktop audio capture requires supported
Windows versions. Streaming quality depends on the source, PC, and connection;
shader postprocessing can make world colors differ from the preview.

## Build

Install JDK 21, then run:

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The playable artifact is
`build/libs/mirror-1.6.1.jar`. The build runs the unit tests and bundles runtime
dependencies. The first build needs internet access to download pinned dependencies.

`src/main` contains shared/server code and assets, `src/client` the Minecraft UI
and renderer, `src/worker` the isolated browser/media engine, and `src/test` the
unit tests. Editable Initiator model sources are in `art/initiator`.

## License

Mirror's source is MIT licensed. Bundled libraries retain their own licenses;
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Corresponding library source
archives are attached to the [source release](https://github.com/hurtorius/Mirror/releases/tag/v1.6.1).

This is not an official Minecraft product and is not approved by Mojang or Microsoft.
