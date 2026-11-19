using System.Text.Json.Serialization;

namespace LanBridge.Models;

public class PairedDevice
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("ip")]
    public string Ip { get; set; } = string.Empty;

    [JsonPropertyName("paired_at")]
    public DateTime PairedAt { get; set; } = DateTime.Now;

    [JsonPropertyName("last_seen")]
    public DateTime LastSeen { get; set; } = DateTime.Now;

    [JsonPropertyName("session_token")]
    public string SessionToken { get; set; } = string.Empty;

    [JsonIgnore]
    public bool IsConnected { get; set; }

    [JsonIgnore]
    public long PingMs { get; set; } = -1;

    [JsonIgnore]
    public string DisplayStatus => IsConnected 
        ? $"Подключено • {PingMs} мс ({Ip})" 
        : $"Не в сети (посл. визит: {LastSeen:dd.MM HH:mm})";
}
