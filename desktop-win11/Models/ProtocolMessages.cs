using System.Text.Json.Serialization;

namespace LanBridge.Models;

public class BaseMessage
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = string.Empty;
}

public class DiscoveryBeacon
{
    [JsonPropertyName("protocol")]
    public string Protocol { get; set; } = "lanbridge";

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0";

    [JsonPropertyName("device_id")]
    public string DeviceId { get; set; } = string.Empty;

    [JsonPropertyName("device_name")]
    public string DeviceName { get; set; } = string.Empty;

    [JsonPropertyName("ws_port")]
    public int WsPort { get; set; } = 8443;

    [JsonPropertyName("cert_fingerprint")]
    public string CertFingerprint { get; set; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }
}

public class AuthMessage : BaseMessage
{
    public const string MessageType = "AUTH";

    [JsonPropertyName("client_id")]
    public string ClientId { get; set; } = string.Empty;

    [JsonPropertyName("client_name")]
    public string ClientName { get; set; } = string.Empty;

    [JsonPropertyName("token")]
    public string Token { get; set; } = string.Empty;

    [JsonPropertyName("protocol_version")]
    public string ProtocolVersion { get; set; } = "1.0";

    public AuthMessage() => Type = MessageType;
}

public class AuthOkMessage : BaseMessage
{
    public const string MessageType = "AUTH_OK";

    [JsonPropertyName("server_id")]
    public string ServerId { get; set; } = string.Empty;

    [JsonPropertyName("server_name")]
    public string ServerName { get; set; } = string.Empty;

    [JsonPropertyName("session_token")]
    public string SessionToken { get; set; } = string.Empty;

    public AuthOkMessage() => Type = MessageType;
}

public class AuthFailMessage : BaseMessage
{
    public const string MessageType = "AUTH_FAIL";

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;

    public AuthFailMessage() => Type = MessageType;
}

public class ClipboardTextMessage : BaseMessage
{
    public const string MessageType = "CLIPBOARD_TEXT";

    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    [JsonPropertyName("content")]
    public string Content { get; set; } = string.Empty;

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    public ClipboardTextMessage() => Type = MessageType;
}

public class ClipboardImageMessage : BaseMessage
{
    public const string MessageType = "CLIPBOARD_IMAGE";

    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    [JsonPropertyName("format")]
    public string Format { get; set; } = "png";

    [JsonPropertyName("width")]
    public int Width { get; set; }

    [JsonPropertyName("height")]
    public int Height { get; set; }

    [JsonPropertyName("data_base64")]
    public string DataBase64 { get; set; } = string.Empty;

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    public ClipboardImageMessage() => Type = MessageType;
}

public class FileOfferMessage : BaseMessage
{
    public const string MessageType = "FILE_OFFER";

    [JsonPropertyName("file_id")]
    public string FileId { get; set; } = Guid.NewGuid().ToString("N");

    [JsonPropertyName("file_name")]
    public string FileName { get; set; } = string.Empty;

    [JsonPropertyName("file_size")]
    public long FileSize { get; set; }

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    [JsonPropertyName("mime_type")]
    public string MimeType { get; set; } = "application/octet-stream";

    public FileOfferMessage() => Type = MessageType;
}

public class FileAcceptMessage : BaseMessage
{
    public const string MessageType = "FILE_ACCEPT";

    [JsonPropertyName("file_id")]
    public string FileId { get; set; } = string.Empty;

    [JsonPropertyName("offset")]
    public long Offset { get; set; } = 0;

    public FileAcceptMessage() => Type = MessageType;
}

public class FileRejectMessage : BaseMessage
{
    public const string MessageType = "FILE_REJECT";

    [JsonPropertyName("file_id")]
    public string FileId { get; set; } = string.Empty;

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;

    public FileRejectMessage() => Type = MessageType;
}

public class FileProgressMessage : BaseMessage
{
    public const string MessageType = "FILE_PROGRESS";

    [JsonPropertyName("file_id")]
    public string FileId { get; set; } = string.Empty;

    [JsonPropertyName("bytes_transferred")]
    public long BytesTransferred { get; set; }

    [JsonPropertyName("total_bytes")]
    public long TotalBytes { get; set; }

    public FileProgressMessage() => Type = MessageType;
}

public class FileCompleteMessage : BaseMessage
{
    public const string MessageType = "FILE_COMPLETE";

    [JsonPropertyName("file_id")]
    public string FileId { get; set; } = string.Empty;

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    public FileCompleteMessage() => Type = MessageType;
}

public class PingMessage : BaseMessage
{
    public const string MessageType = "PING";

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public PingMessage() => Type = MessageType;
}

public class PongMessage : BaseMessage
{
    public const string MessageType = "PONG";

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public PongMessage() => Type = MessageType;
}

public class HistorySyncItem
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "text";

    [JsonPropertyName("content")]
    public string Content { get; set; } = string.Empty;

    [JsonPropertyName("thumbnail_base64")]
    public string? ThumbnailBase64 { get; set; }

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }
}

public class HistorySyncMessage : BaseMessage
{
    public const string MessageType = "HISTORY_SYNC";

    [JsonPropertyName("items")]
    public List<HistorySyncItem> Items { get; set; } = new();

    public HistorySyncMessage() => Type = MessageType;
}
