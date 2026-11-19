using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using LanBridge.Models;

namespace LanBridge.Core;

public class UdpDiscoveryBroadcaster : IDisposable
{
    private const int DiscoveryPort = 54321;
    private readonly string _deviceId;
    private readonly string _deviceName;
    private readonly int _wsPort;
    private readonly string _certFingerprint;
    private CancellationTokenSource? _cts;
    private Task? _broadcastTask;

    public UdpDiscoveryBroadcaster(string deviceId, string deviceName, int wsPort, string certFingerprint)
    {
        _deviceId = deviceId;
        _deviceName = deviceName;
        _wsPort = wsPort;
        _certFingerprint = certFingerprint;
    }

    public void Start()
    {
        if (_broadcastTask != null) return;
        _cts = new CancellationTokenSource();
        _broadcastTask = Task.Run(() => BroadcastLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        try
        {
            _broadcastTask?.Wait(1000);
        }
        catch { }
        _broadcastTask = null;
    }

    private async Task BroadcastLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                var beacon = new DiscoveryBeacon
                {
                    DeviceId = _deviceId,
                    DeviceName = _deviceName,
                    WsPort = _wsPort,
                    CertFingerprint = _certFingerprint,
                    Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
                };

                string json = JsonSerializer.Serialize(beacon);
                byte[] data = Encoding.UTF8.GetBytes(json);

                var targets = GetBroadcastEndpoints();

                using var client = new UdpClient();
                client.EnableBroadcast = true;

                foreach (var ep in targets)
                {
                    try
                    {
                        await client.SendAsync(data, data.Length, ep);
                    }
                    catch { }
                }
            }
            catch { }

            try
            {
                await Task.Delay(2000, token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private List<IPEndPoint> GetBroadcastEndpoints()
    {
        var endpoints = new List<IPEndPoint>
        {
            new IPEndPoint(IPAddress.Broadcast, DiscoveryPort)
        };

        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up ||
                    ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                    continue;

                foreach (var u in ni.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily == AddressFamily.InterNetwork && u.IPv4Mask != null)
                    {
                        var broadcast = GetSubnetBroadcast(u.Address, u.IPv4Mask);
                        if (broadcast != null && !endpoints.Any(e => e.Address.Equals(broadcast)))
                        {
                            endpoints.Add(new IPEndPoint(broadcast, DiscoveryPort));
                        }
                    }
                }
            }
        }
        catch { }

        return endpoints;
    }

    private IPAddress? GetSubnetBroadcast(IPAddress address, IPAddress mask)
    {
        byte[] ipBytes = address.GetAddressBytes();
        byte[] maskBytes = mask.GetAddressBytes();
        if (ipBytes.Length != maskBytes.Length) return null;

        byte[] broadcast = new byte[ipBytes.Length];
        for (int i = 0; i < broadcast.Length; i++)
        {
            broadcast[i] = (byte)(ipBytes[i] | (~maskBytes[i] & 0xFF));
        }
        return new IPAddress(broadcast);
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
