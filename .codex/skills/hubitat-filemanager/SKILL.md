---
name: hubitat-filemanager
description: "Use when the user wants to upload, download, list, or delete files in Hubitat File Manager. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, use Hubitat file manager endpoints, and report file names, sizes, and dates in concise tables."
---

# Hubitat File Manager

Use this skill to manage files stored in a Hubitat hub's File Manager.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Parse the requested operation:
   - `list`
   - `upload local_path [remote_name]`
   - `download remote_name [local_path]`
   - `delete remote_name`
   If omitted, default to `list`.

## Operations

### List

- Call `/hub/fileManager/json`.
- Display files as a markdown table with Name, Size, and Date.
- Sort by name.
- Convert sizes to human-readable units and timestamps to readable dates.

### Upload

- POST multipart form data to `/hub/fileManager/upload` with `uploadFile=@...;filename=...`.
- Default the remote filename to the basename of the local file.
- Report success, size, and resulting `/local/{filename}` URL.

### Download

- GET `/local/{remote_filename}` to a local path.
- Default the local path to `./{remote_filename}`.
- Verify the file exists and has content before reporting success.

### Delete

- This is destructive on the hub. Require explicit user confirmation before deleting.
- POST JSON to `/hub/fileManager/delete` with `{"name":"...","type":"file"}`.
- Report the hub response clearly.

## Reporting

Keep output concise. Use tables for listings and include file size for uploads and downloads.
