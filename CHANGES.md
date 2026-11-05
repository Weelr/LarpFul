# Larpful – fixes in this build
1. Split view: each pane has its own zoom/scroll/pinch/wheel state; a pane reacts only after it is tapped (first tap just focuses it, blue ring shows the focused pane). Toolbar, undo/redo, zoom buttons, add-page, export act on the focused pane.
2. Sidebar: Apple-Notes-style list with thumbnails (notes) and a page-thumbnail grid for the open note (tap to jump). Thumbnails are cached and rendered lazily.
3. Import: native reader for FreeNotes `.freebackup` (ZIP of `.frnote` folders: protobuf metadata/document/layout, `FNPages` ink, source PDFs, `foldertree.frdata`). Generic JSON/ZIP/PDF/image fallback fixed (nested folders, nested/prefixed archives). Failures show a copyable report.
4. Performance: no live blur over the canvas, lazy page canvases (only pages near the viewport are allocated), cached thumbnails, debounced search, rAF-throttled drags, GPU-friendly toggles.
