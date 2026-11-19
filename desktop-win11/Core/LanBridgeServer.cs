using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using LanBridge.Models;

namespace LanBridge.Core;

public class LanBridgeServer : IDisposable
{
    private readonly int _port;
    private readonly X509Certificate2 _serverCertificate;
    private readonly string _serverId;
    private readonly string _serverName;
    private readonly FileStreamingService _fileStreamingService;
    private TcpListener? _tcpListener;
    private CancellationTokenSource? _cts;
    private Task? _listenerTask;

    private WebSocket? _activeWebSocket;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private string? _activeSessionToken;
    private string? _currentPairingToken;
    private readonly ConcurrentDictionary<string, PairedDevice> _pairedDevices = new();
    private readonly ConcurrentDictionary<string, (string FilePath, FileOfferMessage Offer)> _outgoingFileOffers = new();
    private readonly ConcurrentDictionary<string, FileOfferMessage> _incomingFileOffers = new();

    public event Action<PairedDevice>? OnDeviceConnected;
    public event Action<string>? OnDeviceDisconnected;
    public event Action<long>? OnPingUpdated;
    public event Action<ClipboardTextMessage>? OnRemoteClipboardText;
    public event Action<ClipboardImageMessage>? OnRemoteClipboardImage;
    public event Action<FileOfferMessage>? OnRemoteFileOffer;

    public bool IsClientConnected => _activeWebSocket != null && _activeWebSocket.State == WebSocketState.Open;
    public PairedDevice? ConnectedDevice { get; private set; }

    public LanBridgeServer(
        int port,
        X509Certificate2 serverCertificate,
        string serverId,
        string serverName,
        FileStreamingService fileStreamingService)
    {
        _port = port;
        _serverCertificate = serverCertificate;
        _serverId = serverId;
        _serverName = serverName;
        _fileStreamingService = fileStreamingService;
    }

    public void SetPairingToken(string? token)
    {
        _currentPairingToken = token;
    }

    public void AddPairedDevice(PairedDevice device)
    {
        _pairedDevices[device.Id] = device;
    }

    public void RemovePairedDevice(string deviceId)
    {
        _pairedDevices.TryRemove(deviceId, out _);
    }

    public IEnumerable<PairedDevice> GetPairedDevices() => _pairedDevices.Values;

    public void Start()
    {
        if (_tcpListener != null) return;

        _cts = new CancellationTokenSource();
        _tcpListener = new TcpListener(IPAddress.Any, _port);
        _tcpListener.Start();

        _listenerTask = Task.Run(() => AcceptConnectionsLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        try
        {
            _tcpListener?.Stop();
        }
        catch { }
        _tcpListener = null;

        CloseActiveWebSocket();
    }

    private void CloseActiveWebSocket()
    {
        if (_activeWebSocket != null)
        {
            try
            {
                if (_activeWebSocket.State == WebSocketState.Open)
                {
                    _activeWebSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Server stopping", CancellationToken.None)
                        .GetAwaiter().GetResult();
                }
                _activeWebSocket.Dispose();
            }
            catch { }
            _activeWebSocket = null;
        }

        if (ConnectedDevice != null)
        {
            ConnectedDevice.IsConnected = false;
            OnDeviceDisconnected?.Invoke(ConnectedDevice.Id);
            ConnectedDevice = null;
        }
    }

    private async Task AcceptConnectionsLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _tcpListener != null)
        {
            try
            {
                var tcpClient = await _tcpListener.AcceptTcpClientAsync(token);
                _ = Task.Run(() => HandleClientConnectionAsync(tcpClient, token), token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch { }
        }
    }

    private async Task HandleClientConnectionAsync(TcpClient tcpClient, CancellationToken token)
    {
        SslStream? sslStream = null;
        try
        {
            tcpClient.NoDelay = true;
            var netStream = tcpClient.GetStream();
            sslStream = new SslStream(netStream, false);

            var sslOptions = new SslServerAuthenticationOptions
            {
                ServerCertificate = _serverCertificate,
                ClientCertificateRequired = false,
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck
            };

            await sslStream.AuthenticateAsServerAsync(sslOptions, token);

            // Read HTTP request header
            string requestHeader = await ReadHttpRequestHeaderAsync(sslStream, token);
            if (string.IsNullOrEmpty(requestHeader))
            {
                tcpClient.Dispose();
                return;
            }

            string firstLine = requestHeader.Split("\r\n")[0];
            string[] parts = firstLine.Split(' ');
            if (parts.Length < 2)
            {
                tcpClient.Dispose();
                return;
            }

            string method = parts[0].ToUpperInvariant();
            string pathAndQuery = parts[1];
            string path = pathAndQuery.Split('?')[0];

            if (method == "GET" && path.Equals("/ws", StringComparison.OrdinalIgnoreCase))
            {
                await HandleWebSocketUpgradeAsync(sslStream, requestHeader, tcpClient, token);
            }
            else if (method == "POST" && path.Equals("/api/files/upload", StringComparison.OrdinalIgnoreCase))
            {
                await HandleFileUploadAsync(sslStream, pathAndQuery, requestHeader, token);
                tcpClient.Dispose();
            }
            else if (method == "GET" && path.Equals("/api/files/download", StringComparison.OrdinalIgnoreCase))
            {
                await HandleFileDownloadAsync(sslStream, pathAndQuery, token);
                tcpClient.Dispose();
            }
            else
            {
                byte[] notFound = Encoding.UTF8.GetBytes("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                await sslStream.WriteAsync(notFound, token);
                tcpClient.Dispose();
            }
        }
        catch
        {
            try { sslStream?.Dispose(); } catch { }
            try { tcpClient.Dispose(); } catch { }
        }
    }

    private async Task HandleWebSocketUpgradeAsync(SslStream sslStream, string requestHeader, TcpClient tcpClient, CancellationToken token)
    {
        var match = Regex.Match(requestHeader, @"Sec-WebSocket-Key:\s*([^\r\n]+)", RegexOptions.IgnoreCase);
        if (!match.Success)
        {
            tcpClient.Dispose();
            return;
        }

        string secKey = match.Groups[1].Value.Trim();
        string acceptKey = ComputeWebSocketAcceptKey(secKey);

        string response = "HTTP/1.1 101 Switching Protocols\r\n" +
                          "Upgrade: websocket\r\n" +
                          "Connection: Upgrade\r\n" +
                          $"Sec-WebSocket-Accept: {acceptKey}\r\n\r\n";

        byte[] respBytes = Encoding.UTF8.GetBytes(response);
        await sslStream.WriteAsync(respBytes, token);
        await sslStream.FlushAsync(token);

        CloseActiveWebSocket();

        var ws = WebSocket.CreateFromStream(sslStream, isServer: true, subProtocol: null, TimeSpan.FromSeconds(5));
        _activeWebSocket = ws;

        string clientIp = ((IPEndPoint)tcpClient.Client.RemoteEndPoint!).Address.ToString();
        await ProcessWebSocketMessagesAsync(ws, clientIp, token);
    }

    private static string ComputeWebSocketAcceptKey(string secKey)
    {
        string combined = secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        using var sha1 = SHA1.Create();
        byte[] hash = sha1.ComputeHash(Encoding.UTF8.GetBytes(combined));
        return Convert.ToBase64String(hash);
    }

    private async Task ProcessWebSocketMessagesAsync(WebSocket ws, string clientIp, CancellationToken token)
    {
        byte[] buffer = new byte[64 * 1024];
        var ms = new MemoryStream();

        // Start heartbeat loop
        using var heartbeatCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        _ = Task.Run(() => HeartbeatLoopAsync(ws, heartbeatCts.Token), heartbeatCts.Token);

        try
        {
            while (ws.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                ms.SetLength(0);
                WebSocketReceiveResult result;
                do
                {
                    result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), token);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Client closed", token);
                        return;
                    }
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Text)
                {
                    string json = Encoding.UTF8.GetString(ms.ToArray());
                    await HandleIncomingJsonMessageAsync(json, clientIp, token);
                }
            }
        }
        catch { }
        finally
        {
            heartbeatCts.Cancel();
            CloseActiveWebSocket();
        }
    }

    private async Task HandleIncomingJsonMessageAsync(string json, string clientIp, CancellationToken token)
    {
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("type", out var typeProp)) return;

        string type = typeProp.GetString() ?? string.Empty;

        switch (type)
        {
            case AuthMessage.MessageType:
                var auth = JsonSerializer.Deserialize<AuthMessage>(json);
                if (auth != null)
                {
                    bool isOneTimePairing = !string.IsNullOrEmpty(_currentPairingToken) &&
                                           auth.Token == _currentPairingToken;

                    bool isPaired = _pairedDevices.TryGetValue(auth.ClientId, out var existingDevice) &&
                                    existingDevice.SessionToken == auth.Token;

                    if (isOneTimePairing || isPaired)
                    {
                        string sessionToken = Guid.NewGuid().ToString("N");
                        _activeSessionToken = sessionToken;

                        var device = new PairedDevice
                        {
                            Id = auth.ClientId,
                            Name = auth.ClientName,
                            Ip = clientIp,
                            SessionToken = sessionToken,
                            IsConnected = true,
                            LastSeen = DateTime.Now
                        };

                        _pairedDevices[device.Id] = device;
                        ConnectedDevice = device;

                        var okMsg = new AuthOkMessage
                        {
                            ServerId = _serverId,
                            ServerName = _serverName,
                            SessionToken = sessionToken
                        };
                        await SendJsonMessageAsync(okMsg, token);

                        // Reset pairing token after successful one-time use
                        if (isOneTimePairing)
                        {
                            _currentPairingToken = null;
                        }

                        OnDeviceConnected?.Invoke(device);
                    }
                    else
                    {
                        var failMsg = new AuthFailMessage
                        {
                            Reason = "Неверный токен сопряжения или авторизации"
                        };
                        await SendJsonMessageAsync(failMsg, token);
                    }
                }
                break;

            case PingMessage.MessageType:
                var ping = JsonSerializer.Deserialize<PingMessage>(json);
                var pong = new PongMessage { Timestamp = ping?.Timestamp ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
                await SendJsonMessageAsync(pong, token);
                break;

            case PongMessage.MessageType:
                var pongReceived = JsonSerializer.Deserialize<PongMessage>(json);
                if (pongReceived != null && ConnectedDevice != null)
                {
                    long rtt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - pongReceived.Timestamp;
                    ConnectedDevice.PingMs = Math.Max(0, rtt);
                    OnPingUpdated?.Invoke(ConnectedDevice.PingMs);
                }
                break;

            case ClipboardTextMessage.MessageType:
                var textMsg = JsonSerializer.Deserialize<ClipboardTextMessage>(json);
                if (textMsg != null)
                {
                    OnRemoteClipboardText?.Invoke(textMsg);
                }
                break;

            case ClipboardImageMessage.MessageType:
                var imgMsg = JsonSerializer.Deserialize<ClipboardImageMessage>(json);
                if (imgMsg != null)
                {
                    OnRemoteClipboardImage?.Invoke(imgMsg);
                }
                break;

            case FileOfferMessage.MessageType:
                var offer = JsonSerializer.Deserialize<FileOfferMessage>(json);
                if (offer != null)
                {
                    _incomingFileOffers[offer.FileId] = offer;
                    OnRemoteFileOffer?.Invoke(offer);

                    // Auto-accept file offer
                    var accept = new FileAcceptMessage { FileId = offer.FileId, Offset = 0 };
                    await SendJsonMessageAsync(accept, token);
                }
                break;

            case FileAcceptMessage.MessageType:
                var acceptMsg = JsonSerializer.Deserialize<FileAcceptMessage>(json);
                if (acceptMsg != null && _outgoingFileOffers.TryGetValue(acceptMsg.FileId, out var fileData))
                {
                    // Peer is ready to download or will download via HTTP
                }
                break;

            case FileRejectMessage.MessageType:
                var reject = JsonSerializer.Deserialize<FileRejectMessage>(json);
                if (reject != null)
                {
                    _outgoingFileOffers.TryRemove(reject.FileId, out _);
                }
                break;
        }
    }

    private async Task HeartbeatLoopAsync(WebSocket ws, CancellationToken token)
    {
        while (ws.State == WebSocketState.Open && !token.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(5000, token);
                if (ws.State == WebSocketState.Open)
                {
                    var ping = new PingMessage { Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
                    await SendJsonMessageAsync(ping, token);
                }
            }
            catch { break; }
        }
    }

    public async Task SendJsonMessageAsync<T>(T message, CancellationToken token = default)
    {
        if (_activeWebSocket == null || _activeWebSocket.State != WebSocketState.Open) return;

        string json = JsonSerializer.Serialize(message);
        byte[] bytes = Encoding.UTF8.GetBytes(json);

        await _sendLock.WaitAsync(token);
        try
        {
            if (_activeWebSocket.State == WebSocketState.Open)
            {
                await _activeWebSocket.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    token);
            }
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public void RegisterOutgoingFile(string fileId, string filePath, FileOfferMessage offer)
    {
        _outgoingFileOffers[fileId] = (filePath, offer);
    }

    private async Task HandleFileUploadAsync(SslStream sslStream, string pathAndQuery, string requestHeader, CancellationToken token)
    {
        var queryParams = ParseQueryString(pathAndQuery);
        string fileId = queryParams.GetValueOrDefault("file_id", string.Empty);
        string tokenParam = queryParams.GetValueOrDefault("token", string.Empty);

        if (string.IsNullOrEmpty(fileId) || tokenParam != _activeSessionToken)
        {
            byte[] unauthorized = Encoding.UTF8.GetBytes("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n");
            await sslStream.WriteAsync(unauthorized, token);
            return;
        }

        if (!_incomingFileOffers.TryGetValue(fileId, out var offer))
        {
            byte[] notFound = Encoding.UTF8.GetBytes("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
            await sslStream.WriteAsync(notFound, token);
            return;
        }

        var match = Regex.Match(requestHeader, @"Content-Length:\s*(\d+)", RegexOptions.IgnoreCase);
        long contentLength = match.Success ? long.Parse(match.Groups[1].Value) : offer.FileSize;

        await _fileStreamingService.ReceiveFileStreamAsync(
            fileId,
            offer.FileName,
            contentLength,
            offer.Sha256,
            sslStream,
            token);

        _incomingFileOffers.TryRemove(fileId, out _);

        byte[] ok = Encoding.UTF8.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
        await sslStream.WriteAsync(ok, token);

        var completeMsg = new FileCompleteMessage { FileId = fileId, Sha256 = offer.Sha256 };
        await SendJsonMessageAsync(completeMsg, token);
    }

    private async Task HandleFileDownloadAsync(SslStream sslStream, string pathAndQuery, CancellationToken token)
    {
        var queryParams = ParseQueryString(pathAndQuery);
        string fileId = queryParams.GetValueOrDefault("file_id", string.Empty);
        string tokenParam = queryParams.GetValueOrDefault("token", string.Empty);

        if (string.IsNullOrEmpty(fileId) || tokenParam != _activeSessionToken)
        {
            byte[] unauthorized = Encoding.UTF8.GetBytes("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n");
            await sslStream.WriteAsync(unauthorized, token);
            return;
        }

        if (!_outgoingFileOffers.TryGetValue(fileId, out var item) || !File.Exists(item.FilePath))
        {
            byte[] notFound = Encoding.UTF8.GetBytes("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
            await sslStream.WriteAsync(notFound, token);
            return;
        }

        var fileInfo = new FileInfo(item.FilePath);
        string headers = $"HTTP/1.1 200 OK\r\n" +
                         $"Content-Length: {fileInfo.Length}\r\n" +
                         $"Content-Type: {item.Offer.MimeType}\r\n" +
                         $"Connection: close\r\n\r\n";

        byte[] headerBytes = Encoding.UTF8.GetBytes(headers);
        await sslStream.WriteAsync(headerBytes, token);

        await _fileStreamingService.SendFileStreamAsync(item.FilePath, fileId, sslStream, token);

        _outgoingFileOffers.TryRemove(fileId, out _);

        var completeMsg = new FileCompleteMessage { FileId = fileId, Sha256 = item.Offer.Sha256 };
        await SendJsonMessageAsync(completeMsg, token);
    }

    private static Dictionary<string, string> ParseQueryString(string pathAndQuery)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        int qIdx = pathAndQuery.IndexOf('?');
        if (qIdx < 0 || qIdx >= pathAndQuery.Length - 1) return dict;

        string query = pathAndQuery.Substring(qIdx + 1);
        string[] pairs = query.Split('&');
        foreach (var p in pairs)
        {
            string[] kv = p.Split('=');
            if (kv.Length == 2)
            {
                dict[Uri.UnescapeDataString(kv[0])] = Uri.UnescapeDataString(kv[1]);
            }
        }
        return dict;
    }

    private static async Task<string> ReadHttpRequestHeaderAsync(Stream stream, CancellationToken token)
    {
        byte[] buffer = new byte[1024];
        var ms = new MemoryStream();
        int bytesRead;

        while ((bytesRead = await stream.ReadAsync(buffer, token)) > 0)
        {
            ms.Write(buffer, 0, bytesRead);
            string str = Encoding.ASCII.GetString(ms.ToArray());
            if (str.Contains("\r\n\r\n"))
            {
                return str;
            }
            if (ms.Length > 8192) break; // header limit
        }
        return string.Empty;
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
        _sendLock.Dispose();
    }
}
