# Research notes: ads, video and stories in Facebook Lite 530.0.0.8.106

English notes from the debug-log rounds of October 2026. The older Vietnamese [HANDOFF.md](HANDOFF.md) covers fonts and the first
sponsored-post work. Everything here is for `530.0.0.8.106`; obfuscated names change with every build. A new chat should read this
file first, then ask for a fresh debug log ([DEBUG_LOG.md](DEBUG_LOG.md)).

## 1. How the app builds screens

- Every screen is a server-driven **component tree** (`X.0gp` nodes, `X.0gw` containers, `X.0hT`/`X.0hW` text, children from
  `X.0gn.AF0()`). The root is `0h5#30005`, its list container is `0gw#30001`. Feed, Reels and Marketplace all use these ids.
- A list on screen is an `X.0zb` (a RecyclerView). `A05` is its layout, `A05.A0E` the item list (class `X.0yV`, **no `toArray`**, read by
  index), `A05.A03` the current index, `A07.A02` the snap mode (0 = feed, 1 = Reels), `A08` the snap handler (`A04(index)` scrolls).
- An item (`X.0z8`) holds its component tree at `A0B.A03`. In the Home feed each item **is** one child of container 30001, so hiding a
  post means flagging that child hidden (`A2c`) and giving it height 0 (`A0i`).
- Component fields used: `A1b` id, `A0v` group, `A0i` height, `A29` width, `A2c` hidden, `A18` parent, `A2X`/`A2k` markers, `A32` flag on
  text, `A1V` a `HashMap` some nodes hold. Text is read with `AIW()`; it has style markup (a backslash plus one character) and control
  characters, which `FeedItems.clean` strips.
- Videos are native `com.facebook.lite.widget.video.FbVideoView` (obfuscated subclass `X.1Kt`), children of the list view.
- The app has very few Views. Home feed list is `1080x1991`, Reels list `1080x2093` (snap mode 1), Marketplace list `1080x1989`.
- Lists change while read: never iterate them live (`ConcurrentModificationException`), never call `toArray` on them.

## 2. Reels

- The Reels list holds, besides reels (`X.0gw`, 50 to 60 nodes with creator, Follow, caption), **placeholder items** of 1 node
  (`X.0hT`, `X.0hW`, `X.0nH`) that are not snappable. A swipe jumps over them, so the current index moves by 2 to 3 without any patch
  involved. Not every `JUMP` in the log is a bug.
- Items are rebound by the server's index updates: the tree at an index can change while the item object stays.
- Some reel-sized trees have 29 nodes and no text at all (seen at indices 5, 20, 25, 30, 35 of one session). They may be unbound reels or
  sponsored reels; unknown. The old rule cached "organic" for any tree it saw first, so a placeholder that later held an ad was never
  re-checked. Fixed: an organic verdict is kept only for trees of 40+ nodes.
- User observation (unverified): sponsored reels seem to be inserted right after the reel being watched, while watching.
- No sponsored reel appeared in the logs of 2026-10-22, so the text rule (two flagged text nodes) is untested in this build. The ad
  video mark in section 4 is the planned certain signal.

## 3. Video sources and quality

- `FbVideoView.A0F` is the props (`X.0t0`): `A0Y` the **DASH manifest XML** (all quality steps), `A0c` the progressive SD file (360p,
  tag `xpv_progressive...sve_sd`), `A0b` the video id, `A0a` a tracking JSON. `A0F.A03` (`X.0y5`) repeats them (`A07` manifest, `A09` JSON,
  `A0A` id, `A0B` SD url). The full-screen player has the same in its command object (`A05`).
- Manifests list 144p to 1080p, but the video is almost always **AV1** (`av01...`), sometimes VP9, with **xHE-AAC** audio
  (`mp4a.40.42`). Only a few videos also have H.264 (`dash_baseline`). The old joiner only accepted H.264 + AAC, hence "only 360p".
- `MediaMuxer` cannot write AV1/VP9/xHE-AAC into MP4 before Android 14. `Mp4Remux` reads plain or fragmented MP4 tracks and writes one
  MP4 by copying samples (sample tables rebuilt, `stsd` copied). Tested with ffmpeg-made AV1, VP9 and H.264 (B-frames, plain and
  fragmented): every packet identical, decodes clean. H.264 + AAC still uses `MediaMuxer`.
- xHE-AAC and AV1 may not play in every player; the picker shows the codec.
- File names: `Owner-Type-d MMM_yyyy.mp4`. Owner is the first name-like text of the item tree (Reels: the current item; others: the item
  whose tree refers to the video's props). Type is Reel (video in a snapping list), Story (video fills the window outside a list), else Video.

## 4. Ads

- **Ad video mark**: the tracking JSON of an ad video starts `{"adid":"1202...","qid":...`; organic videos start `{"qid":...,"mf_story_key":...`.
  Seen on a Marketplace video (a booking.com ad; tapping it opened `FBLiteBrowserLiteInMainProcessActivity`, WebView `X.1qB`). This is
  language independent. `AdVideos` checks every FbVideoView child of every list, finds the item whose tree refers to the props (or to
  its JSON or id), and hides the card (non-paging lists) or marks the reel sponsored (Reels).
- **Marketplace layout** (Marketplace tab): the list has `FbVideoView 536x536` and a nested `X.0zb 538x538` as direct children, so it is a
  two-column grid; a card is about half the item width. Hide target = the highest part of the item that is at most 65% of its width.
  Whether the whole row or one card is the item is still to be confirmed from a log (`AD VIDEO belongs to item ...` lines).
- Feed rule (older, kept): a group header with `A2X && A2k` and references (`A0M`), hides the group. The log lines `FEED HIDDEN by rule,
  but no label found` (nodes=2) show it also hides small non-labelled groups; check for false positives.
- Text fallback: a "Sponsored" label in about 30 languages is logged (`SPONSORED-TEXT`) but not used to hide anything.

## 5. Stories

- A story video is a fullscreen `FbVideoView 1080x2211` directly under a FrameLayout, with a small `X.0zb 1080x121` beside it (not the
  Reels list). A story photo shows **no** video and only that small list among the interesting views. Where the photo address lives is
  unknown. `StoryPhotos` searches the large views by value for CDN picture addresses and logs every candidate (`story` tag); `ads IMAGE`
  lines list picture addresses found in stand-alone component trees. Next step: read those lines.

## 6. Download button

- Positioned on the video's own bounds by translation, updated every frame (`postOnAnimation`). The old version used the clipped visible
  rectangle, so the button slid down from the top while a Reel slid in.
- A story photo uses the same button, downloading to `Pictures/Facebook Lite`.

## 7. Debugging method

- Debug logging switch in Morphe settings; `Save log to Downloads`. `adb` and `logcat` are not needed (ColorOS hides app logs).
- Compile check without Gradle: JDK 21 with `java -m jdk.compiler/com.sun.tools.javac.Main -cp android.jar ...` (android.jar from the
  Sable/android-platforms repo), extensions compile on their own; stubs for `X.*` are in `extensions/hideads/stub`.
- `Mp4Remux` tests: ffmpeg (pip `imageio-ffmpeg`) makes AV1/VP9/H.264 + AAC test tracks; compare `ffmpeg -c copy -f framecrc` of the inputs and the output.

## 8. Open questions

1. Does hiding by `A2c` and `A0i = 0` after layout (not at decode time) really close the gap in Marketplace and in Reels (hide mode)?
   Look for `AD VIDEO hiding` and `HIDE reel` lines, then ask the user what they saw.
2. Reel ads: do they carry `adid` in the video props, and when does the next reel's video view attach (before it becomes current)?
3. Where is a story photo's address, and where is the story owner's name (for the file name)?
4. What are the 29-node empty reel trees?
5. Do AV1 + xHE-AAC files play in the user's players?
