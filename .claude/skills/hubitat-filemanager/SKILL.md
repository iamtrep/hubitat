---
name: hubitat-filemanager
description: Upload, download, list, or delete files on the Hubitat hub's File Manager
argument-hint: "[upload|download|list|delete] [filepath]"
allowed-tools: Bash, Read, Glob, Grep
---

# Hubitat File Manager Skill

Manage files on the Hubitat hub's File Manager. Files stored here are accessible at `http://{hub_ip}/local/{filename}` and can be read/written by apps via `downloadHubFile()` / `uploadHubFile()`.

## Instructions

Follow these steps exactly:

### Step 1: Read Configuration

Read `.hubitat.json` from the project root. Parse the multi-hub config:

1. Check if `$ARGUMENTS` starts with `@hubname` (e.g., `@chalet upload myfile.html`). If so, use that hub name and strip the `@hubname` from arguments before further parsing. Otherwise, use `default_hub`.
2. Look up the hub in `hubs[hubname]` to get `hub_ip`.
3. If the hub has `username` and `password` (non-null), it has hub security enabled. Authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to **all** subsequent curl commands for this hub.

### Step 2: Determine the Operation

Parse `$ARGUMENTS` (after stripping any `@hubname`) for the operation and file:

- `upload {local_filepath}` — upload a local file to the hub
- `upload {local_filepath} {remote_filename}` — upload with a different remote name
- `download {remote_filename}` — download a file from the hub
- `download {remote_filename} {local_filepath}` — download to a specific local path
- `list` — list all files on the hub
- `delete {remote_filename}` — delete a file from the hub

If no operation is specified, default to `list`.

### Step 3: Execute the Operation

#### Upload

Upload a local file to the hub's File Manager:

```bash
curl -s -X POST "http://{hub_ip}/hub/fileManager/upload" \
  -F "uploadFile=@{local_filepath};filename={remote_filename}"
```

- `{local_filepath}` is the path to the local file
- `{remote_filename}` defaults to the basename of the local file if not specified
- The response on success: `{"success":true,"status":"Successfully uploaded {filename}"}`
- Report the result, including file size

#### Download

Download a file from the hub's File Manager:

```bash
curl -s -o {local_filepath} "http://{hub_ip}/local/{remote_filename}"
```

- `{local_filepath}` defaults to `./{remote_filename}` if not specified
- Verify the download succeeded by checking the file exists and has content
- Report the result, including file size

#### List

List all files in the hub's File Manager:

```bash
curl -s "http://{hub_ip}/hub/fileManager/json"
```

The response is a JSON object with a `files` array: `{"files": [{"name": "...", "size": "...", "date": "...", "id": "...", "type": "file"}, ...]}`. The `size` is in bytes (string). The `date` is a Unix timestamp in milliseconds (string).

Display as a markdown table with columns: **Name**, **Size**, **Date**.

Sort by name alphabetically. Format file sizes in human-readable form (KB/MB). Convert the timestamp to a readable date.

#### Delete

Delete a file from the hub's File Manager by POSTing a JSON body with the file name:

```bash
curl -s -X POST "http://{hub_ip}/hub/fileManager/delete" \
  -H "Content-Type: application/json" \
  -d '{"name":"{remote_filename}","type":"file"}'
```

**Always confirm with the user before deleting.** Report the result.

### Step 4: Report Result

- On success: report what was done, including file name and size
- On error: report the error message from the hub response
- For uploads: note that the file is now accessible at `http://{hub_ip}/local/{filename}`
