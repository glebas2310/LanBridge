# LanBridge Network & Security Protocol Specification
**Version:** 1.0.0  
**Transport:** UDP Broadcast (Discovery), WSS over TLS 1.3/1.2 (Control & Clipboard), HTTPS/TCP Streaming (File Transfer)

---

## 1. Overview & Architecture

LanBridge is a peer-to-peer (P2P) protocol designed for instantaneous bidirectional clipboard synchronization and high-speed streaming file transfer between a Windows 11 PC and an Android device within a local network (LAN / Wi-Fi Hotspot) without internet access, third-party clouds, or account registration.

### Network Topology
```
+-----------------------------------------------------------+
|                      Local Area Network                   |
|                                                           |
|  +--------------------+         UDP:54321 (Broadcast)    |
|  | Windows 11 Host    | --------------------------------> |
|  | (WSS/HTTPS Server) |         WSS:8443 (TLS 1.3/1.2)    |
|  |                    | <===============================> |
|  +--------------------+         HTTPS:8443 /api/files/*   |
|                         <-------------------------------> |
|                                                           |
|                                 +----------------------+  |
|                                 | Android Mobile Client|  |
|                                 | (WSS/HTTPS Client)   |  |
|                                 +----------------------+  |
+-----------------------------------------------------------+
```

---

## 2. Discovery Protocol (UDP Broadcast)

The Windows 11 PC acts as the local service broadcaster. Every **2.0 seconds**, the PC broadcasts a UDP beacon packet to port `54321`.

### Multicast / Broadcast Strategy
To ensure reliable operation across various router setups (home routers with client isolation or AP hotspot mode created on either Android or Windows):
1. Packet is sent to the global broadcast address: `255.255.255.255:54321`.
2. Packet is also sent to subnet directed broadcast addresses of all active IPv4 network interfaces (e.g. `192.168.1.255`, `192.168.43.255`).

### UDP Beacon Packet Format (JSON)
```json
{
  "protocol": "lanbridge",
  "version": "1.0",
  "device_id": "win_d7a8f9c2",
  "device_name": "DESKTOP-WIN11",
  "ws_port": 8443,
  "cert_fingerprint": "SHA256:7B8F9A1029384756CDEF0123456789ABCDEF0123456789ABCDEF0123456789AB",
  "timestamp": 1700000000
}
```

#### Fields:
- `protocol`: Constant string `"lanbridge"`.
- `version`: Protocol version string (e.g. `"1.0"`).
- `device_id`: Unique persistent identifier of the Windows PC (e.g. machine GUID hash or generated UUID).
- `device_name`: Human-readable hostname of the PC.
- `ws_port`: TCP port used for WSS control channel and HTTPS file transfers (default: `8443`).
- `cert_fingerprint`: SHA-256 fingerprint of the PC's TLS X.509 certificate in uppercase hexadecimal with `SHA256:` prefix.
- `timestamp`: Unix epoch timestamp in seconds.

---

## 3. Pairing & Trust Establishment (TOFU + Pinning)

Security uses a Trust-On-First-Use (TOFU) model backed by Certificate Pinning and one-time authentication tokens.

### 3.1 Certificate Generation on Windows PC
Upon initial startup:
1. Windows 11 PC generates a self-signed X.509 certificate (`CN=LanBridge-PC, O=LanBridge`) with RSA 2048-bit or ECDSA P-256 keys, valid for 10 years, supporting TLS 1.3 and TLS 1.2.
2. Subject Alternative Names (SAN) include `localhost`, `127.0.0.1`, and the PC's current local IP addresses.
3. The certificate and private key are stored securely in `%APPDATA%\LanBridge\cert.pfx`.
4. The SHA-256 digest of the DER-encoded certificate is calculated as `cert_fingerprint`.

### 3.2 Pairing QR Code Specification
When pairing mode is triggered on the PC, a one-time cryptographic token (`one_time_token`) is generated (32 hexadecimal characters). A QR code is rendered encoding the following URI:

```
lanbridge://pair?id=<device_id>&name=<device_name>&ip=<ip_address>&port=<port>&fp=<cert_fingerprint>&token=<one_time_token>
```

#### Example URI:
```
lanbridge://pair?id=win_d7a8f9c2&name=DESKTOP-WIN11&ip=192.168.1.120&port=8443&fp=SHA256:7B8F9A1029384756CDEF0123456789ABCDEF0123456789ABCDEF0123456789AB&token=e9f4c810d2948b37e51c8a6f91d03b5a
```

### 3.3 Android Pairing & Storage
1. Android scans the QR code.
2. Parses parameters: `id`, `name`, `ip`, `port`, `fp`, `token`.
3. Opens a WSS connection to `wss://<ip>:<port>/ws`.
4. Custom X509TrustManager validates that the server's certificate SHA-256 matches the pinned `fp`.
5. Android sends an `AUTH` message containing `token`.
6. Upon receiving `AUTH_OK`, Android stores:
   - `device_id`
   - `device_name`
   - `cert_fingerprint`
   - `auth_token` (long-term session token returned by PC during `AUTH_OK`)
   in Android `EncryptedSharedPreferences` / `Android Keystore`.
7. Subsequent connections use the stored `auth_token` and `cert_fingerprint` without needing another QR scan.

---

## 4. Control & Clipboard Transport (WSS)

- **Endpoint:** `wss://<host>:<port>/ws`
- **Encoding:** UTF-8 JSON text frames.
- **Heartbeat:** `PING` every 5 seconds; client expects `PONG` within 3 seconds.
- **Reconnection:** Exponential backoff (1s, 2s, 4s, 8s, up to max 30s) with auto-trigger upon network change or UDP beacon receipt.

### 4.1 Message Schemas

#### 1. AUTH (Client -> Server)
```json
{
  "type": "AUTH",
  "client_id": "and_98c4f21b",
  "client_name": "Pixel 8 Pro",
  "token": "e9f4c810d2948b37e51c8a6f91d03b5a",
  "protocol_version": "1.0"
}
```

#### 2. AUTH_OK (Server -> Client)
```json
{
  "type": "AUTH_OK",
  "server_id": "win_d7a8f9c2",
  "server_name": "DESKTOP-WIN11",
  "session_token": "a4d8c721f0b9e83716d5c4b3a210fedc"
}
```

#### 3. AUTH_FAIL (Server -> Client)
```json
{
  "type": "AUTH_FAIL",
  "reason": "Invalid pairing or session token"
}
```

#### 4. CLIPBOARD_TEXT (Bidirectional)
Transfers plain text, code snippets, or URLs.
```json
{
  "type": "CLIPBOARD_TEXT",
  "id": "clip_b8c71e20",
  "content": "https://github.com/project/lanbridge",
  "sha256": "4b9f29103c8e41a27e...",
  "timestamp": 1700000050
}
```

#### 5. CLIPBOARD_IMAGE (Bidirectional)
Transfers copied screenshots or bitmaps.
```json
{
  "type": "CLIPBOARD_IMAGE",
  "id": "img_f902ac34",
  "format": "png",
  "width": 1920,
  "height": 1080,
  "data_base64": "iVBORw0KGgoAAAANSUhEUgAA...",
  "sha256": "3a8c7b91e204d8f5...",
  "timestamp": 1700000060
}
```

#### 6. FILE_OFFER (Bidirectional)
Notifies the peer about an incoming file.
```json
{
  "type": "FILE_OFFER",
  "file_id": "file_82c401be",
  "file_name": "document.pdf",
  "file_size": 14589200,
  "sha256": "8f3e2b901a4c...",
  "mime_type": "application/pdf"
}
```

#### 7. FILE_ACCEPT (Bidirectional)
Recipient accepts the file and specifies the transfer mechanism.
```json
{
  "type": "FILE_ACCEPT",
  "file_id": "file_82c401be",
  "offset": 0
}
```

#### 8. FILE_REJECT (Bidirectional)
```json
{
  "type": "FILE_REJECT",
  "file_id": "file_82c401be",
  "reason": "Declined by user"
}
```

#### 9. FILE_PROGRESS (Bidirectional)
In-band telemetry update (optional, also computed directly from stream read/write).
```json
{
  "type": "FILE_PROGRESS",
  "file_id": "file_82c401be",
  "bytes_transferred": 7340032,
  "total_bytes": 14589200
}
```

#### 10. FILE_COMPLETE (Bidirectional)
Confirms successful end-to-end receipt and verified hash.
```json
{
  "type": "FILE_COMPLETE",
  "file_id": "file_82c401be",
  "sha256": "8f3e2b901a4c..."
}
```

#### 11. HISTORY_SYNC (Bidirectional)
Synchronizes the latest history items (max 25) upon connection.
```json
{
  "type": "HISTORY_SYNC",
  "items": [
    {
      "id": "clip_b8c71e20",
      "kind": "text",
      "content": "Meeting notes at 15:00",
      "sha256": "5c9e...",
      "timestamp": 1700000040
    }
  ]
}
```

#### 12. PING / PONG (Bidirectional Heartbeat)
```json
{
  "type": "PING",
  "timestamp": 1700000100
}
```
```json
{
  "type": "PONG",
  "timestamp": 1700000100
}
```

---

## 5. File Transfer Streaming Protocol (HTTPS / Chunked TCP)

Files are transferred directly using continuous streaming:
- **Chunk Size:** 64 KB (`65536` bytes).
- **RAM Efficiency:** Zero-buffering. Stream directly from file descriptor to network socket and network socket to file descriptor.
- **Verification:** SHA-256 computed on-the-fly during read and write; final digest must match `FILE_OFFER.sha256`.

### Endpoints on Windows Server:
- `POST /api/files/upload?file_id=<file_id>&token=<session_token>`:
  Used by Android to stream a file up to the Windows PC. Streamed raw binary in HTTP request body.
- `GET /api/files/download?file_id=<file_id>&token=<session_token>`:
  Used by Android to stream a file down from Windows PC. Streamed raw binary in HTTP response body.

Both endpoints support HTTP/1.1 chunked transfer or known `Content-Length`.

---

## 6. Echo-Loop Guard

To prevent infinite clipboard bounce loops between devices:
1. Every clipboard item (text or image) is hashed using SHA-256.
2. Both clients maintain a thread-safe variable `last_remote_sha256` and a recent history set of received hashes.
3. When receiving remote clipboard data:
   - `last_remote_sha256` is updated to the received hash.
   - The data is written to the system clipboard (`Clipboard.SetDataObject` on Windows, `ClipboardManager.setPrimaryClip` on Android).
4. When the local clipboard monitor fires:
   - The local data is read and its SHA-256 is computed.
   - If `local_sha256 == last_remote_sha256` or exists in the recent remote hashes set, the event is immediately discarded.
   - Otherwise, the new item is dispatched to the network and added to local history.
