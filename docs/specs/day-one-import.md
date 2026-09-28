# Day One import profile, revision 1

This is Someday's supported **Day One JSON export/import compatibility profile**,
not a specification of Day One's private synchronization protocol. The normative
rules below are the baseline for `DayOneArchiveReader`, `DayOneImportService`, and
`DayOneMediaResolver`. Unknown fields do not imply support for their semantics.
Changes to this profile must update its synthetic contract tests.

## Evidence and scope

- [Day One export guide](https://dayoneapp.com/guides/tips-and-tutorials/exporting-entries/):
  JSON ZIP exports may optionally contain media directories.
- [Day One JSON import guide and official example](https://dayoneapp.com/guides/tips-and-tutorials/importing-data-from-json-files/):
  the linked `2023-2-2-Journal.zip` has version `1.0`, seven entries, and twelve
  photo records resolving to ten JPEG files by `md5` and `type`.
- [Day One staff explanation of multiple journals](https://forums.dayoneapp.com/forums/topic/importing-json/):
  one JSON file per journal, multiple journals in one ZIP, MD5-named photos.
- A private, locally inspected export has four journals, 935 entries, 448
  string-encoded rich-text documents, 129 photo records, and four audio records.
  Its 109 JPEGs and one M4A do **not** all resolve from those records. Only one
  photo record and one audio record match files. The ZIP uses ZIP64 member sizes
  even though the archive is only about 11 MiB. It is diagnostic evidence, not a
  distributable fixture or proof that every export has this shape.
- [A matching upstream report](https://forums.dayoneapp.com/forums/topic/pdfs-without-pictures-strange-json-export-picture-mapping/)
  describes filenames differing from JSON MD5 values. Do not invent an alternate
  identity scheme to compensate for such exports.

Private diary text, photos, locations, and original archives must not be committed
as fixtures. Tests generate synthetic archives and use an innocuous one-pixel PNG.

## Archive envelope

Accept one unencrypted, single-disk ZIP, using stored or raw DEFLATE members,
UTF-8 names, optional data descriptors, and ZIP64 size/offset extra fields.
ZIP64 end-of-directory records are supported as well. A small ZIP is not evidence
that its member sizes fit in the ordinary header fields.

Discover journal `.json` members, ignoring dot-prefixed path components and
`__MACOSX`. The JSON basename supplies the journal title. For a journal in a
wrapper directory, its media directories are siblings in that same directory:

```text
Export/
  Journal.json
  photos/<md5>.<type>
  audios/...
  videos/...
  pdfs/...
```

Never extract paths to the host filesystem. Reject absolute paths, backslashes,
drive prefixes, traversal components, control characters, and case-insensitive
duplicate paths. Match the local-header name/method/flags against the directory.
Check bounds, inflated size, and CRC32 of every member actually read. Read images
lazily; unrelated or unreferenced media are not imported.

Resource bounds: 512 MiB encoded archive, 100,000 archive members, 16 MiB per
journal JSON, 64 MiB total JSON, and 100,000 notes per import. Inflation is bounded
by the declared size, not an unbounded `readByteArray`. For larger exports, export
smaller selections. Existing platform pickers load archive bytes in memory; this
is not a streaming multi-gigabyte importer.

## Journal and entry records

The root must have an `entries` array. `metadata.version`, when present, must be
the string `"1.0"`. Missing metadata/version is accepted for legacy compatibility;
unknown explicit versions fail rather than importing an empty journal.

| Field | Conversion rule |
| --- | --- |
| `uuid` | Required, unique across this import. Preserve identity as `dayone-<uuid>`. Accept bounded ASCII alphanumeric/hyphen IDs (including legacy non-UUID IDs); native exports use 32 hexadecimal characters. |
| `creationDate` | Required valid timestamp; never replace an invalid date with the current time. |
| `modifiedDate` | Valid timestamp if present; otherwise use creation time. |
| `timeZone` | Preserve a valid zone ID; missing/blank means absent. Invalid IDs fail. |
| `text` | Optional Markdown representation, not necessarily formatting-free text. |
| `richText` | Optional JSON encoded **inside a string**; see below. |
| `location` | Preserve latitude/longitude and available place/address components; use entry creation time as capture time. |
| `photos`, `audios`, `videos`, `pdfs` | Optional lists of attachment records. Absence of files does not invalidate a note. |
| `tags`, `starred`, `isPinned`, `weather` | Count in the import report; do not claim to restore unsupported Someday semantics. |
| Other fields | Ignore; do not log their values. |

Retain Someday's existing source identity scheme, including case/whitespace-
normalized journal-title grouping and its existing journal IDs. Title is derived
from the first nonblank converted line, up to 80 characters; blank notes use
`Day One <creation date in the entry time zone>`. Missing content is a valid blank
note. Empty journals use the Unix epoch for their synthetic creation time.

Decode and validate all journal records before starting conversion. Duplicate
entry IDs fail, rather than silently dropping one copy. Parser errors must not
include source JSON, diary text, or deserializer excerpts.

## Body conversion

Prefer supported, valid `richText`; otherwise use `text` and count a rich-text
fallback. Never concatenate the two representations. If nonempty malformed or
unsupported rich text is the only body, fail rather than silently erase it.

Supported rich text has `meta.version: 1` (or absent for legacy compatibility) and
an ordered `contents` array. Nodes may contain text, attributes, and embedded
objects. Preserve content order, inline runs, and explicit newlines.

- `attributes.line.header`: 1–6 become Markdown headings; **0 is not a heading**.
  A repeated heading attribute on an inline run must not create another heading.
- `bold`, `italic`, `underline`: Markdown emphasis or `<u>` markup; keep surrounding
  whitespace outside emphasis delimiters. Underline is retained in source, not a
  promise that every Someday preview renders HTML.
- `autolink`: preserve HTTP(S) links as Markdown links; no network requests.
- `line.checked`: checklist; `listStyle`: `bulleted` or `numbered`; `indentLevel`
  1 is the first level, with bounded additional indentation.
- Embedded `photo`, `audio`, `video`, `pdf`: resolve as described below.
- Embedded `markdown`: retain string contents (`markdown`, `text`, or `contents`).
- `horizontalRuleLine`: `---`.
- Other embedded types: explicit unsupported-object placeholder and a report count.

This is a supported subset, not a lossless implementation of every Day One editor
feature or all CommonMark constructs. Existing Markdown text and embedded Markdown
are retained. Rewrite exported inline links with `dayone-moment://<identifier>`;
code spans/fences and unrelated links remain literal. Imported images become
standalone lines so Someday's image preview can render them. Do not fetch external URLs.

## Attachment resolution and media-optional exports

The identity chain is:

```text
body dayone-moment://ID or embeddedObjects[].identifier
  -> this entry's attachment record with identifier == ID
  -> photos/<record.md5>.<record.type> beside the journal JSON
  -> verify MD5 of actual bytes
  -> validate/normalize through Someday's existing static-image pipeline
  -> ![Photo](someday-asset://<Someday asset ID>)
```

`identifier` and `md5` are different identities. Never substitute `filename`,
archive order, image dimensions, approximate dates, or a visually similar photo.
MD5 is used only for Day One compatibility, not as Someday's asset identity or a
security boundary. Verify type from the file content, not the extension alone.

Import only static JPEG (`jpeg`/`jpg`), PNG, and WebP. Apply the existing selected-
image bounds (32 MiB/200 MP input) and normalization to the existing persisted
4 MiB/12 MP bounds. No schema, encryption, media wire, or publication changes.
Audio, video, and PDF remain explicit unsupported-attachment placeholders.

Preserve each occurrence in the chosen body, while resolving/storing a file only
once per archive path. Append metadata-only attachments after the body, sorted by
`orderInEntry`. The attachment array itself is not ordered. Ambiguous duplicate
identifiers are unresolved, never guessed.

Report photo results per distinct attachment reference in each entry, not per
body occurrence or unique file:

| Result | Behavior |
| --- | --- |
| Imported | Durable Someday asset reference at the original position. |
| Missing | Expected file absent, including exports made without media. Keep `[Photo: ID — file not included]`. |
| Unresolved | Missing/ambiguous attachment record, invalid/missing MD5, or missing type. Keep an unresolved-reference placeholder. |
| Rejected | Present but corrupt, hash/type mismatch, unsupported, over limits, or cannot be normalized safely. Keep an invalid/unsupported-image placeholder. |

There is no reliable “the user intentionally omitted media” flag in this profile.
Therefore missing files are nonfatal whether intentional or accidental. A folder
of unmatchable images is not permission to associate them arbitrarily. A
text-only converter without a media writer also retains placeholders.

## Someday persistence and verification

Conversion produces Someday `LocalDataExportDocument` notes with Markdown bodies,
timestamps, timezone, and location. This is a source-import DTO, **not** a change
to portable Someday backup: portable exports still omit image bytes.

Production platform runners already perform import IO off the UI thread. The
System V3 composition holds the existing workspace-lifecycle lock across image
writes and note import. Images use `AuthorityCoordinatedMediaAssetStore`; notes
use `WorkspaceLocalDataTransferV2`, the existing DAG-backed import path. Avoid
nested acquisition of the non-reentrant lifecycle lock. No fallback note store.

Missing/rejected source attachments do not abort valid text. Before writing any
notebook or note, the DAG importer preflights every converted entity using the
same normalization, payload validation, and envelope bounds as the actual write.
Invalid target content (including UTF-8 body or location bounds) rejects the
import before those writes. This does not add a whole-archive database
transaction: existing source-import replay/conflict semantics remain in force.
Unexpected media-storage or authority failures abort rather than becoming image
placeholders. Staging-file open, read, and close failures propagate before the
platform codec receives bounded in-memory bytes; codec rejection of those bytes
remains a rejected image. Cancellation propagates without image-error conversion.
Failures during notebook/note persistence carry the confirmed
counts from earlier writes; Day One returns `completed = false` with those counts.
Assets written before a later failure may remain unreferenced for existing orphan
cleanup. Import never deletes or replaces the current workspace.

The UI distinguishes completed, partial, failed-before-persistence, cancelled,
and unavailable results. Partial imports refresh the product view and show only
confirmed persistence counts, not whole-file conversion/media totals. Failed or
cancelled attempts show no fabricated zero-count report. Successful reports show
imported, missing, unresolved, and rejected photos separately from other media
and unsupported content, using localized UI resources rather than parser prose.

Required synthetic evidence lives in `DayOneArchiveReaderTest`,
`DayOneImportContractTest`, and `DayOneImportServiceTest`: ZIP/ZIP64 variants,
CRC/bounds/path rejection, exact Markdown output, missing media, hash mismatch,
inline repetition, metadata ordering, unsupported media, version/fallback rules,
timezone boundaries, and persistence-error propagation. Integration evidence must
also exercise the production DAG/media composition and repeat import.

Local private-export verification must use an in-memory conversion sink or a
disposable database, print only counts, and leave the original archive and user's
Someday workspace untouched. Cross-platform compile checks cover Android,
Desktop, and iOS; a successful JVM parser test alone is not iOS evidence.

The three local conversion checks use a memory-only image writer and confirm:

| Sample | Notes converted | Matched photo references | Distinct matched files |
| --- | ---: | ---: | ---: |
| Private media export | 935 | 1 | 1 |
| Earlier private media-free export | 932 | 0 | 0 |
| Official example | 7 | 12 | 10 |

The media-free export also omits attachment fields such as `type`; these are
unresolved references, not evidence of corrupt images. The official example has
one additional orphan body reference with no corresponding photo record.

`DayOneWorkspaceImportTest` independently verifies real durable image bytes,
Someday asset links in DAG notes, repeat-import deduplication, and workspace
lifecycle exclusion using synthetic data and a disposable schema-aware database.
It also checks preflight rejection and an injected mid-import storage failure
followed by a successful retry without duplicate notes, staging open/read/close
failures before normalization and decoding followed by successful image import
on retry, and nonfatal rejection of corrupt image bytes.
`DayOneImportContentTest` covers localized complete-image, missing-image, partial,
failed, and cancelled result states at narrow screen width.

Local iOS acceptance on an isolated iPhone 17 / iOS 26.5 simulator imported the
private media export through the document picker: 935 notes, four notebooks,
507 locations, and one image. Restart and repeat import created no new notes
and skipped all 935. Read-only database/file checks verified image references,
SHA-256, and complete native decoding. Separate synthetic UI checks exercised
picker cancellation, invalid-ZIP retry, media-free text, and image previews after
restart, including an oversized image normalized below the 12 MP bound. These
checks kept sync off and did not modify the user's existing workspace.
