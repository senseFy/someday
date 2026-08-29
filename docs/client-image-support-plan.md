# Client Image Support Plan

Status: implementation and automated gates are complete.
Real-client UI acceptance remains before release.
No server or wire-protocol change is expected.

## 1. Outcome

Complete client image support means that a user can:

1. choose a supported image while editing a note;
2. keep the note and its app-owned image usable offline;
3. preview the image without exposing a filesystem path or remote URL;
4. save and synchronize the Markdown reference;
5. open the note on another paired device, explicitly download the image, and
   see the same authenticated immutable asset; and
6. understand and retry supported failure cases without inspecting logs.

The server and synchronization layers already provide encrypted media objects,
media-before-note publication, lazy downloads, storage bounds, and remote
persistence. This work completes the client experience around them.

## 2. Supported behavior

The client supports:

- selected static JPEG, PNG, and WebP sources only;
- at most 32 MiB and 200,000,000 declared pixels for a selected source;
- at most 4 MiB and 12,000,000 decoded pixels for the final stored and
  synchronized asset;
- one immutable app-owned asset per imported image;
- Markdown reference format
  `![alt](someday-asset://<64-lowercase-hex-asset-id>)`;
- a source already inside the final byte and pixel limits is preserved exactly;
- a larger source is normalized before import, and only the normalized result
  becomes the immutable app-owned asset;
- image bytes are encrypted before self-hosted publication;
- referenced media is remotely durable before its entity version becomes
  visible;
- receiving note text does not automatically download image bytes;
- HTTP image URLs are never fetched by Someday; and
- portable export and restore continue to omit image bytes.

Removing a Markdown reference does not delete the local or remote object.
Published media is currently append-only. An image imported into an abandoned
note may remain as a harmless local immutable asset.

The 32 MiB source limit protects image acquisition and memory use. The protocol
stores at most 4 MiB per image. A source that cannot be reduced to that bound
without violating the quality rules in section 6.5 is rejected before an asset
or Markdown reference is created.

## 3. Baseline and implementation result

| Capability | Starting state | Implementation result |
| --- | --- | --- |
| Bounded local import | Implemented with byte inspection, full decode validation, app-private atomic promotion, and content deduplication | Added bounded source staging, passthrough-or-normalize, final validation, cleanup, and typed failures |
| Markdown insertion | Implemented with selection-aware canonical asset syntax | Kept the current source format and session guard |
| Editor action | Implemented as an image toolbar action | Kept one action, with silent success/cancellation and actionable failure feedback |
| Local preview | Implemented from verified immutable asset bytes | Consolidated the verified bounded read under the workspace lifecycle lock |
| Missing-image UI | Implemented with explicit user-requested materialization | Added local-read retry while preserving explicit materialization |
| Android acquisition | Implemented with a document picker | Uses the AndroidX single-image Photo Picker and its platform fallback |
| iOS acquisition | Implemented with a document picker | Uses the single-image Photos picker with a compatible representation |
| Desktop acquisition | Implemented with a native file dialog | Retained the chooser and extracted its media adapter from the entry point |
| Cross-device media sync | Implemented and covered by the real self-hosted journey | Wire and server behavior remain unchanged; real-client UI acceptance is pending |
| UI behavior tests | Parser and controller tests exist | Extended controller tests and added focused Compose preview, download, retry, and remote-URL tests |

## 4. Scope

### Included

- one image per picker invocation; a note may contain multiple images by
  repeating the action;
- selection of supported source images up to 32 MiB and 200 MP;
- lossless passthrough when the source already fits the final limits;
- quality-bounded normalization when its bytes or pixel count exceed the final
  limits;
- the system photo picker on Android and iOS;
- the native file chooser on Desktop;
- safe filename-derived alt text when available;
- local preview of an imported or materialized asset;
- explicit remote materialization when local bytes are missing or corrupt;
- actionable localized errors for source size, format, invalid encoding,
  normalization, local read failure, and download failure;
- retry after preview or materialization failure;
- Android, iOS, and Desktop parity for the complete flow; and
- real Mac/Android plus iOS simulator acceptance against the self-hosted server.

### Not included

- video, audio, PDFs, SVG, GIF/APNG/animated WebP, or general attachments;
- camera capture, multi-select, paste, drag and drop, crop, annotation, or an
  image gallery;
- a Someday-owned HEIC/AVIF decoder or transcoding pipeline;
- user-selectable compression quality, output format, or resolution;
- automatic download merely because a note became visible;
- remote URL fetching;
- per-image upload progress, a second upload outbox, resumable upload, or
  background transfer infrastructure;
- image bytes in portable backup/restore; and
- local reachability GC or remote object deletion.

These capabilities can be added separately through the existing picker,
preview, or protocol boundaries.

## 5. User flows

### 5.1 Insert

```text
Idle -> Picking -> Preparing -> Importing -> Inserted
                  |            |             |
                  |            |             +-> canonical Markdown block at current selection
                  |            +-> Failed -> actionable message, Markdown unchanged
                  +-> Failed -> actionable message, Markdown unchanged
Picking -> Cancelled -> Markdown unchanged, no feedback banner
```

- The image action is disabled while one request is active.
- A picker result applies only to the editor session that opened it.
- Preparing is an internal passthrough-or-normalize decision. It does not add a
  quality dialog or another persistent upload state.
- Successful insertion is visible in the editor and needs no separate success
  message.
- Picker cancellation is a normal exit and remains silent.
- The suggested alt text is a safe filename stem when one exists. The user can
  edit it directly in Markdown; there is no additional metadata dialog.

### 5.2 Preview and download

```text
Loading local -> Ready
              -> Missing -> Downloading -> Ready
                                       -> Download failed -> Retry
              -> Read failed -> Retry local preview
Unsupported destination -> Static placeholder, no network request
```

- Preview reads only app-owned `someday-asset://` identities.
- A missing or corrupt local asset offers an explicit Download action.
- Download success immediately retries the local preview.
- The UI shows one bounded image with `ContentScale.Fit`; it does not interpret
  the image as an external link.
- Alt text is used as the accessibility description when present.

### 5.3 Save, synchronize, and remove

- Saving a note remains an ordinary DAG mutation. Image import does not create
  another note model or save path.
- Existing System V3 reachability checks publish every referenced asset before
  the selected note version or checkpoint.
- The existing note sync badge represents media-gated publication failures.
  There is no separate image upload status model.
- Deleting the Markdown line removes the image from the note presentation. It
  does not delete immutable storage.

## 6. Client architecture

### 6.1 Responsibilities

- `shared:domain` owns canonical asset identity, URI parsing, and fixed limits.
- `shared:data` owns bounded source staging, inspection, normalization policy,
  final decode validation, metadata, and app-private immutable storage.
- `shared:sync` owns workspace coordination, publication, and materialization.
- `shared:ui` owns picker/preview ports and user-visible state.
- platform applications own native picker presentation and platform decoding.

`LocalMediaAssetStore` remains the media persistence boundary; UI state does not
duplicate durable media state.

### 6.2 One coordinated preview read

Replace the duplicated `loadBoundedPreview` implementations in Android,
Desktop, and iOS with one shared operation exposed through
`AuthorityCoordinatedMediaAssetStore`:

```text
readVerifiedPreview(assetId, byteLimit, pixelLimit)
  -> Loaded(bytes)
   | Missing
   | Corrupt
   | TooLarge
   | Failed
```

The operation must hold `WorkspaceLifecycleCoordinator.productAccess` while it
loads metadata, verifies the file, and copies the bounded bytes. Returning an
open `Source` to UI code would release the lifecycle lock before the read and
allow workspace replacement to race with preview IO.

The shared operation also removes duplicated byte/pixel checks and local-state
mapping. Sync publication may continue to stream an immutable asset while it
already holds the exclusive workspace lifecycle boundary.

### 6.3 Typed safe failures

The UI must not classify failures by parsing exception messages. Preserve
specific machine-readable reasons through the import and preview path:

- `SourceTooLarge`;
- `UnsupportedFormat`;
- `AnimatedImage`;
- `SourcePixelLimitExceeded`;
- `InvalidEncoding`;
- `NormalizationFailed` or `NormalizationWouldViolateQualityBounds`;
- `LocalMissing` or `LocalCorrupt`;
- `PreviewTooLarge`;
- `PreviewReadFailed`; and
- `MaterializationFailed`.

The presentation layer may combine reasons that have the same user action. It
must not display platform paths, content URIs, filenames rejected as unsafe,
asset bytes, Markdown content, endpoints, credentials, or raw transport
diagnostics.

### 6.4 Platform acquisition

- Android uses the system single-image Photo Picker. The AndroidX API may
  fall back to the platform document picker on unsupported devices. Someday
  copies the returned stream immediately into app-private storage and does not
  retain a content URI.
- iOS uses the system Photos picker with one selection and a compatible asset
  representation. The returned representation is copied immediately into
  app-private storage.
- Desktop retains its native single-file chooser.
- Every platform result still passes the same bounded shared signature,
  animation, byte, and source-dimension inspection. The final candidate passes
  the existing full-decode validation. Picker filtering is not a security or
  correctness boundary.

The platform may provide a compatible representation, but Someday does not add
its own HEIC/AVIF codec. If the supplied source is not a valid static JPEG, PNG,
or WebP image, import fails with an actionable message before normalization.

Reference material:

- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Apple preferred asset representation](https://developer.apple.com/documentation/photosui/phpickerconfiguration-c.class/preferredassetrepresentationmode)

### 6.5 One bounded normalization policy

`LocalMediaAssetStore` remains the only component that stages and promotes
immutable media. Extend its import path with one injected
`MediaImageNormalizer` platform boundary instead of giving each picker its own
storage or compression flow:

```text
copy selected stream to app-private staging (hard stop at 32 MiB)
  -> inspect source type, animation, dimensions, and orientation
  -> valid and <= 4 MiB and <= 12 MP: validate and promote exact bytes
  -> otherwise: sampled decode -> orient -> resize/encode -> inspect and fully
     decode final candidate -> promote
```

The source metadata inspection may accept up to 200 MP, but the normalizer must
never allocate a source-sized bitmap merely to reduce it. It decodes directly
toward the 12 MP target using the platform sampling APIs. Temporary source and
candidate files are removed after success, cancellation, or failure.

The source pixel bound uses a separate constant and inspection mode.
`MAX_MEDIA_ASSET_PIXEL_COUNT` continues to limit stored metadata, previews,
sync manifests, and server validation to 12 MP.

Normalization uses these fixed rules:

- preserve orientation and aspect ratio, and never upscale;
- JPEG and opaque WebP sources normalize to JPEG at a fixed quality of 88;
  fitting the byte limit reduces dimensions rather than repeatedly lowering
  quality;
- PNG remains PNG, and transparent WebP normalizes to PNG, so transparency and
  crisp graphics are not silently flattened into JPEG;
- first reduce to at most 12 MP, then reduce dimensions further only when the
  encoded result still exceeds 4 MiB;
- never reduce the long edge below `min(source long edge, 2,048 pixels)` merely
  to meet the byte bound; reject the source instead;
- inspect and fully decode the final candidate through the existing importer;
- normalized output preserves rendered pixels, orientation, aspect ratio, and
  alpha where applicable, but does not promise to retain camera metadata; and
- never retain the pre-normalized source as a durable media object.

This is a bounded conversion, not an adaptive media pipeline: no background
jobs, variants, server negotiation, or user-facing quality choices. Android
uses sampled bitmap decoding and native encoding. iOS and Desktop use the
already bundled Skia codec to decode toward a bounded surface and encode the
result; a codec that rejects direct scaled decode is rendered from its lazy
encoded image into that surface instead of allocating an explicit source-size
bitmap. The shared policy and fixtures must agree across platforms, but
byte-identical encoder output across operating systems is not required. Once
imported, the chosen output bytes are immutable and synchronize exactly.

If real-client acceptance shows that representative photographs cannot meet
4 MiB at the fixed quality and minimum dimension, raise the media limit only in
a separately versioned System V3/server change. Do not silently lower quality
or increase the wire limit inside this client project.

Reference material:

- [Android sampled bitmap decoding](https://developer.android.com/reference/android/graphics/BitmapFactory.Options)

### 6.6 Preview memory

The wire limit remains 12 MP. Platform preview loaders decode the verified
bounded bytes off the UI thread and return a correctly oriented `ImageBitmap`;
Android applies EXIF orientation explicitly where the platform decoder does not.
Before release, the maximum accepted fixtures must preview without an
out-of-memory failure on the target Android device, iOS simulator/device, and
macOS client. If the full decode cannot meet that acceptance criterion,
implement sampled platform decoding behind the existing `MediaPreviewLoader`.
Preview sampling does not change the stored asset, media identity, or wire
limit.

## 7. Implementation work packages

### A. Shared media result model

- Define one canonical client byte limit in `shared:domain` and consume it from
  data and UI code while preserving the System V3 wire assertion.
- Add the source acquisition limits and a `MediaImageNormalizer` boundary.
- Keep the 200 MP source-inspection bound separate from the unchanged 12 MP
  domain and wire bound.
- Keep source staging, passthrough, final validation, atomic promotion, and
  temporary cleanup in one `LocalMediaAssetStore` import transaction.
- Add typed inspection/import failure reasons in `shared:data`.
- Add the bounded, lifecycle-coordinated preview read in `shared:sync`.
- Route all three platform preview loaders through it.

Primary files:

- `shared/domain/.../media/MediaAssets.kt`
- `shared/data/.../media/LocalMediaAssetStore.kt`
- `shared/data/.../media/StaticImageMediaInspector.kt`
- `shared/sync/.../AuthorityCoordinatedMediaAssetStore.kt`
- `shared/ui/.../media/MediaUiPorts.kt`

### B. Platform adapters

- Move media picker/import/preview wiring out of `MainActivity`, Desktop
  `Main.kt`, and `MainViewController` into focused platform adapter files.
- Adopt the mobile system photo pickers described above.
- Keep stream copying and storage work off the UI thread.
- Implement the shared normalization policy with platform-native sampled decode
  and encoding; do not fully decode oversized source dimensions.
- Guarantee exactly one callback for success, cancellation, or failure.
- Map typed shared failures to `MediaImportUiResult` without raw exception text.

### C. Shared editor and preview UI

- Preserve canonical standalone Markdown insertion and editor-session checks.
- Remove success and cancellation banners; show only actionable failures.
- Keep the action disabled during import.
- Extract image preview presentation into a focused internal component.
- Add retry for local preview failures and download failures.
- Keep remote-image destinations as non-interactive placeholders.
- Add or update English, Simplified Chinese, Japanese, and Korean resources in
  the same change.

### D. Tests and acceptance

- Add the cases in section 8 at the lowest layer that proves each behavior.
- Run the real self-hosted image journey and both System V3 release gates.
- Complete the real-client acceptance matrix before calling the work done.

### E. Documentation and release

- Update README client image status only after the acceptance matrix passes.
- Update `docs/sync-system-v3-spec.md` or `docs/self-hosted-media-v3.md` if the
  implementation changes wire behavior.
- Release this as a client change. `server-v0.1.0` remains compatible unless a
  separate server or protocol defect is found.

## 8. Test plan

### Shared domain and data

- canonical asset URI parsing and reference discovery;
- exact 32 MiB source acceptance and one-byte-over rejection;
- exact 200 MP source metadata acceptance and first-over-limit rejection;
- exact 4 MiB final acceptance and one-byte-over source normalization;
- exact 12 MP final acceptance and first-over-limit source normalization;
- valid JPEG, PNG, and WebP;
- disguised extension/MIME, truncated bytes, bad container structure, and full
  decoder rejection;
- GIF, SVG, APNG, animated WebP, and unsupported HEIC/AVIF rejection;
- typed failure reason stability;
- byte-for-byte passthrough when a valid source already fits;
- normalization preserves orientation and aspect ratio without upscaling;
- opaque normalization uses the fixed JPEG quality;
- transparent normalization preserves alpha;
- normalization meets the final byte/pixel limits or rejects without creating
  an asset;
- oversized sources are sampled without a source-sized bitmap allocation;
- source and candidate staging files are cleaned after every terminal result;
- atomic import, deduplication, restart, missing file, and corrupt file; and
- bounded preview read racing with workspace replacement.

### Shared UI

- image action starts once and is disabled until completion;
- picker cancellation and failure never mutate Markdown;
- a stale callback cannot mutate another editor session;
- insertion preserves selection, newline boundaries, escaping, and canonical
  identity;
- local preview covers loading, ready, missing, corrupt, too large, decode
  failure, and retry;
- materialization covers success, failure, retry, and duplicate callback
  suppression;
- HTTP destinations never invoke preview or materialization ports; and
- accessibility text uses alt text and remains valid when alt text is empty.

### Platform adapters

- selected stream is copied on a background dispatcher;
- cancellation completes exactly once;
- picker hints cannot bypass byte-signature validation;
- each platform passes the shared passthrough and normalization fixture suite;
- a temporary URI/path is not retained after import;
- platform decode dimensions match inspected dimensions; and
- application entry points contain only composition wiring.

### Self-hosted journey

Retain the existing real journey and prove:

1. device A imports an image, saves a note, and synchronizes;
2. the remote media object exists before the note version is published;
3. device B synchronizes the note without eagerly downloading the image;
4. explicit materialization returns the exact immutable asset bytes;
5. restart preserves the preview;
6. a missing local file can be rematerialized; and
7. workspace replacement cannot commit preview/import work from the discarded
   workspace.

Run the journey once with exact passthrough bytes and once with a source that
requires normalization. Device B must receive the exact immutable output stored
by device A; it does not need access to the discarded selected source.

### Real-client acceptance

Run against the released local Docker server:

| Flow | macOS | Android | iOS simulator/device |
| --- | --- | --- | --- |
| Choose JPEG/PNG/WebP and preview offline | Required | Required | Required |
| Preserve an in-bound source byte-for-byte | Required | Required | Required |
| Normalize a representative large phone photo with acceptable visible quality | Required | Required | Required |
| Preserve orientation, aspect ratio, and alpha where present | Required | Required | Required |
| Reject over-32-MiB, over-200-MP, unnormalizable, animated, and invalid input | Required | Required | Required |
| Save, restart, and preview | Required | Required | Required |
| Sync from device A and explicitly download on device B | Required | Required | Required |
| Retry after offline download failure | Required | Required | Required |
| Preview the largest accepted fixture without memory failure | Required | Required | Required |

## 9. Required gates

At minimum:

```bash
make lint
make check
make client-smoke
make shared-smoke
make sync-v3-gate
make sync-v3-apple-gate
```

Also run `scripts/verify-system-v3-architecture` directly while iterating on
client media boundaries. Any SQLDelight schema change is unexpected; if one
becomes necessary, it requires a numbered migration and the migration gate.

## 10. Completion criteria

The client image project is complete when:

- all included user flows work on Android, iOS, and Desktop;
- all supported formats and exact bounds have deterministic tests;
- in-bound sources pass through unchanged and larger supported sources follow
  the fixed quality-bounded normalization policy;
- local preview and materialization are lifecycle-safe;
- failures are typed, localized, retryable where appropriate, and do not leak
  sensitive input;
- the real-client matrix and System V3 gates pass without skips;
- server and protocol behavior remain compatible; and
- the implementation uses the bounded immutable-image model described here.
