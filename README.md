# InkNote

InkNote is a native Android blogging application built around offline writing. Users can create and manage messages on their device, attach a photo, share a message by email, and publish selected messages to a connected online account.

The project is developed in **Android Studio using Java and XML layouts**, with **SQLite as the local source of truth**.

> **Status:** In development. This README describes the agreed requirements and design scope. It does not indicate that every feature or screen is already implemented.

## Project goals

- Make writing, reading, editing, searching, and deleting available without an internet connection.
- Keep message data and attached images on the device.
- Provide a clear, accessible interface for phones and tablets.
- Keep saving, sharing, and online publishing as separate user actions.
- Develop the application incrementally, using the existing shared components for each new screen.

## Features

### Required features

| Feature | Expected behaviour |
| --- | --- |
| Offline writing | Create a blog message without internet access. |
| Message list | Display locally stored messages, newest first with a stable ordering. |
| Individual view | Open and read the complete message. |
| Editing | Update an existing message while preserving its identity and creation date. |
| Search | Search message text and titles where present, then display matching messages. |
| Single deletion | Delete one chosen message. |
| Group deletion | Select and delete a chosen group of messages. |
| Local persistence | Store messages and metadata in on-device SQLite. |
| Photo attachment | Attach an image from the gallery or camera. |
| Email sharing | Pass one message's text and optional image through Android's standard Share mechanism so an email app can be selected. |
| Online publishing | Upload one message or a selected group to a supported personal account on a mainstream public platform. |

Firebase, cloud databases, remote backup, and automatic cloud synchronisation are outside the project scope. User-initiated online publishing is a separate assignment requirement.

### Additional design features

- Local draft autosave for new messages.
- Favourites and tags.
- Sorting and search filters.
- Word count and estimated reading time.
- Trash, restore, undo, and permanent deletion.
- Upload queue and per-message upload outcomes.
- Upload history.
- Light, dark, and system-default appearance.

These additions should reuse the existing models and services where appropriate. Add database tables and migrations when a feature is implemented, rather than creating unused speculative structures.

## Technology

| Area | Technology or approach |
| --- | --- |
| IDE | Android Studio |
| Application language | Java |
| UI | XML layouts, AndroidX, Material Components for Android Views |
| View access | View Binding |
| Screen state | ViewModel and LiveData |
| Message lists | RecyclerView |
| Persistence | SQLite with SQLiteOpenHelper |
| Models | Java POJOs with constructors and accessors |
| Background operations | Shared I/O executor |
| Retained images | App-managed local files with metadata in SQLite |
| Image sharing | FileProvider content URIs and temporary read access |
| Diagnostics | Reusable logger with sanitised messages |

Use the SDK, JDK, Android Gradle Plugin, and dependency versions configured in the actual project. Preserve its existing package names and Gradle script format.

## Architecture

UI components observe screen state through a ViewModel. The ViewModel invokes a service on the I/O executor. Services validate input and coordinate persistence through the database access layer.

| Layer | Responsibility |
| --- | --- |
| Activity / Fragment | Render UI, collect input, handle navigation, and launch Android system flows. |
| ViewModel | Expose screen state, invoke services, and manage asynchronous work. |
| Service | Apply validation and coordinate message, database, and attachment operations. |
| DAO / existing repository | Own SQL, bound arguments, cursor mapping, and database transactions. |
| SQLiteOpenHelper | Create and configure the database and apply data-preserving migrations. |
| Model | Represent messages, attachments, and feature-specific data as Java POJOs. |
| Utilities | Provide focused helpers such as logging, execution, and file handling. |

Keep SQL out of Activities, Fragments, ViewModels, and adapters. If the existing code uses a repository, keep a clear responsibility for it; do not add another layer that only duplicates DAO or service methods.

Database, file, and network operations must run off the main thread. Long-lived services and ViewModels must not retain Activity views or Fragment bindings.

### Local data

The initial domain includes a message model, such as `BlogPost`, and attachment metadata, such as `PostAttachment`. Follow the names and fields already established in the source code.

Data-handling rules:

- A title is optional; the message body must be nonblank when completing a message.
- Preserve the user's formatting and support Myanmar and other Unicode text.
- Keep creation and update times consistent.
- Bind user input instead of concatenating it into SQL.
- Treat literal search characters such as `%` and `_` correctly.
- Delete only the selected IDs during grouped operations.
- Use transactions where database changes must succeed together.
- Preserve existing messages during database upgrades.
- Coordinate image-file cleanup separately from database transactions.

## Screen reference

The design contains **18 numbered screen references**. These do not require 18 separate Android Activities.

| No. | Screen | Purpose |
| --- | --- | --- |
| 01 | Welcome | Introduce the app and enter the local library. |
| 02 | Library Home | List messages, filter drafts/favourites, and start writing. |
| 03 | Search and Filters | Find local messages and narrow the results. |
| 04 | New Message | Write a message and save a local draft. |
| 05 | Edit Message | Update an existing local message. |
| 06 | Message Details | Read a message and access edit, share, upload, or delete actions. |
| 07 | Attach Photo | Choose a gallery image or take a photo. |
| 08 | Select Multiple Messages | Select messages for group deletion or publishing. |
| 09 | Share Message by Email | Preview content and open the Android Sharesheet. |
| 10 | Connected Publishing Account | Authorise or disconnect the external publishing account. |
| 11 | Review Single Upload | Review one message and its destination before publishing. |
| 12 | Review Batch Upload | Validate and review a group of separate posts. |
| 13 | Upload Queue | View pending work and individual upload outcomes. |
| 14 | Upload History | Review previous publishing attempts. |
| 15 | Upload Result Details | Inspect a confirmed or uncertain publishing result. |
| 16 | Tags and Favourites | Organise and retrieve messages. |
| 17 | Trash and Restore | Restore messages or permanently delete them. |
| 18 | Settings and Appearance | Change appearance and writing preferences. |

Screens 07 and 09 are bottom sheets or related UI flows. Screen 08 is a selection state of the library. Use the numbers to identify design references, not as text in the finished app.

## UI and accessibility

The visual direction uses a calm lavender theme, readable text, softly rounded surfaces, and generous spacing.

| Theme role | Light appearance |
| --- | --- |
| Primary | `#6750A4` |
| Background | `#FAF8FF` |
| Surface | `#FFFFFF` |
| Main text | `#201B2A` |
| Secondary text | `#655F71` |
| Error | `#B3261E` |

Design goals include:

- At least 48dp touch targets.
- Clear labels and logical screen-reader focus.
- Sufficient contrast and status indicators that do not rely on colour alone.
- Support for large text, including 200% system font scaling.
- Phone, landscape, tablet, and split-screen layouts.
- Controls that remain visible when the keyboard opens.
- Clear saving feedback, error recovery, Cancel, and Undo.
- A navigation rail and list/detail arrangement where the available window size permits.

## Getting started

1. Clone or download the repository.
2. Open its root directory in Android Studio. This is the directory containing the Gradle wrapper and settings file.
3. Allow Gradle to sync.
4. Install any Android SDK components requested by the project configuration.
5. Select the project-compatible Gradle JDK in Android Studio.
6. Start an emulator or connect an Android device with USB debugging enabled.
7. Select the `app` run configuration and click **Run**.

No account is required for local writing. Online publishing requires a separately configured integration.

### Build from the command line

Run commands from the Android project root.

**Windows PowerShell**

```powershell
.\gradlew.bat assembleDebug
```

**Linux / macOS**

```bash
./gradlew assembleDebug
```

For the standard `app` module and debug variant, the generated APK is normally located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Sharing and publishing

### Email sharing

Share one message through the standard Android Sharesheet. Include the text and the attached image, when present.

Use a readable content URI with temporary permission for the receiving application. Keep the email address, subject editing, and final Send action in the chosen email app.

Opening the chooser or email application does not confirm that an email was sent.

### Online publishing

LinkedIn is the example destination in the design. A working implementation requires a supported personal-account flow, authorised API access, and the necessary permissions.

- Connect an account only when the user requests the publishing feature.
- Review the destination and outgoing content before uploading.
- Treat a group as separate posts, with an outcome for each message.
- Preserve device copies when publishing fails.
- Mark Published only after a confirmed platform response.
- Treat an uncertain result as Check status and verify the account before retrying.
- Editing or deleting a device copy does not automatically alter an online post.
- Do not hardcode credentials, tokens, or confidential client secrets.

Any alternative or custom platform must meet the assignment's approval requirements.

## Logging

Use the shared logger to record sanitised operation names, outcomes, counts, and failures where useful.

Do not log message bodies, titles, search text, images, private paths, credentials, access tokens, or sensitive account information. Keep release logging limited and avoid recording the same exception repeatedly across layers.

## Verification

Run the relevant checks as tests and features are added. For the standard debug variant:

**Windows PowerShell**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

**Linux / macOS**

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Device tests require a running emulator or connected device. Successful task execution alone does not prove that tests exist for every feature.

### Manual checks

- Create, view, edit, search, and delete messages with internet access disabled.
- Restart the application and confirm that saved messages remain available.
- Check Myanmar text, long messages, empty input, and literal wildcard searches.
- Confirm that group deletion affects only selected messages.
- Attach gallery and camera images; verify cancellation and missing-image recovery.
- Restart the app and confirm retained images remain readable.
- Share text and an image to an installed email app.
- Verify single and batch publishing with a configured test account.
- Test failed, partially completed, and uncertain uploads without losing local content.
- Check restore and permanent deletion where Trash is implemented.
- Check rotation, large text, dark mode, tablet layouts, and keyboard overlap.
- Verify that a database migration preserves existing data.

## Development workflow

1. Establish the shared models, database helper, persistence layer, services, utilities, and application wiring.
2. Implement each supplied screen using those existing components.
3. Add only the queries, resources, migrations, and navigation needed for that screen.
4. Connect UI actions to real SQLite-backed services.
5. Verify the completed behaviour and record the actual result.
6. Continue with the next supplied screen.

The screen list is a design reference; implementation may follow dependency order. Keep each increment buildable and avoid references to missing screens, resources, or classes.

