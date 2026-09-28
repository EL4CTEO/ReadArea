# Security

ReadArea opens files that anyone can make, so it treats every book as untrusted input. This page describes
what it defends against and how, so you know what to expect and what to report.

## Reporting a problem

Please report security issues privately through GitHub: **Security → Report a vulnerability** on this
repository. Include the file or steps that trigger the problem, the ReadArea version and your system. You'll
get an answer, and credit in the release notes if you'd like it.

## What ReadArea promises

- **No network.** The apps contain no networking code. The Android app doesn't request internet access; the
  desktop app never opens a connection. Nothing is sent anywhere, ever.
- **A book can't break out.** Opening a file, damaged or deliberately malicious, either works or fails with
  a "can't open" message. It can't crash or hang the app, exhaust memory, run code, read other files or send
  anything anywhere.
- **Your data stays yours.** The library, reading progress and notes live in a folder only your user account
  can open, and nothing in them is shared.

## How

**Parsing books.** All formats are parsed by ReadArea's own Kotlin parsers (PDFs by Apache PDFBox), with:

- limits on file size (128 MB of text), on each archive entry (32 MB), on the total markup an EPUB may expand
  to (256 MB) and on entry counts, against decompression bombs;
- nesting depth capped at 256 levels in XML and HTML (8 for CSS media queries), against stack exhaustion and
  quadratic blow-ups;
- text scanners that stay linear on hostile input: Markdown emphasis, links and rules and CSS comments are
  matched by hand-written scans or bounded patterns, not regexes that backtrack or recurse per repetition;
- no DTDs or external entities processed at all (only character references like `&amp;` are decoded);
- every offset and length read from a binary header (MOBI, PalmDOC) checked before use;
- images decoded at most 16,384 pixels a side and 24 megapixels, subsampled if larger;
- a single boundary where any parser failure becomes the app's own error, so no malformed file can surface
  an unexpected exception;
- metadata (titles, authors, descriptions) bounded in length and cleared of control characters and
  text-direction overrides before it's stored or shown.

These are tested: `core/src/test/.../security` holds an exploit test for each attack, a mutation fuzzer
that runs thousands of damaged books in every format, and a complexity fuzzer that repeats random patterns
of each format's syntax into large books and fails if opening them takes more than linear time; `desktop/src/test/.../security` does the same for PDFs,
comics and the desktop app's own surface.

**Nothing leaves the book.** Pictures come only from inside the book (or, for Markdown and HTML files, from
their own folder, with links and symlinks that point elsewhere refused). Web addresses in books are never
fetched. Fonts inside books are never loaded (only fonts you import yourself). Scripts and forms aren't
supported, so nothing in a book can run code.

**Links.** Only web (`http`, `https`) and email (`mailto`) links open, always after you confirm. The
confirmation shows the address in plain ASCII, so hidden direction marks or look-alike characters can't
disguise where it goes; addresses with non-ASCII host names or over 2,000 characters are refused.

**Read aloud.** Text goes to the system's speech program through its standard input (on Windows, base64 into
a fixed script), never on a command line, with control characters and speech-engine commands removed.

**The desktop app's own surface.**

- Helper programs (file managers, speech, dark-mode checks) run from fixed system paths with argument
  lists, never through a shell or a PATH lookup.
- A second launch hands files to the running app over a socket inside the private data folder, with a random
  token compared in constant time; requests are small, time-limited and only ever name existing files.
- Library folders are scanned without following links, to a fixed depth.
- SQLite and FlatLaf native libraries load from the app's own folder, not unpacked into shared temp folders.
- Images are decoded without disk caches; the JVM writes no performance data to the shared temp folder.

**Supply chain.** Dependencies come from Maven Central, and from Google's Maven repository only for Google's
own packages. Dependabot proposes updates weekly. Release builds run in GitHub Actions from the
tagged commit and are published with SHA-256 checksums.
