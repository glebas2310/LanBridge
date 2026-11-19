using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using LanBridge.Core;
using LanBridge.Models;
using QRCoder;

namespace LanBridge.UI;

public partial class PairingWindow : Window
{
    private readonly LanBridgeServer _server;
    private readonly string _deviceId;
    private readonly string _deviceName;
    private readonly string _certFingerprint;
    private string _currentOneTimeToken = string.Empty;
    public ObservableCollection<PairedDevice> PairedDevices { get; } = new();

    public PairingWindow(
        LanBridgeServer server,
        string deviceId,
        string deviceName,
        string certFingerprint)
    {
        InitializeComponent();

        _server = server;
        _deviceId = deviceId;
        _deviceName = deviceName;
        _certFingerprint = certFingerprint;

        PairedDevicesListView.ItemsSource = PairedDevices;
        FingerprintText.Text = _certFingerprint;

        RefreshDevicesList();
        GenerateNewPairingQr();
    }

    private void RefreshDevicesList()
    {
        PairedDevices.Clear();
        foreach (var dev in _server.GetPairedDevices())
        {
            PairedDevices.Add(dev);
        }
    }

    private void GenerateNewPairingQr()
    {
        _currentOneTimeToken = Guid.NewGuid().ToString("N");
        _server.SetPairingToken(_currentOneTimeToken);

        var localIps = CertificateManager.GetLocalIPv4Addresses();
        string primaryIp = localIps.FirstOrDefault(ip => !ip.StartsWith("127.")) ?? "127.0.0.1";
        HostIpText.Text = string.Join(", ", localIps);

        // lanbridge://pair?id=<id>&name=<name>&ip=<ip>&port=8443&fp=<sha256_fingerprint>&token=<one_time_token>
        string uri = $"lanbridge://pair?id={Uri.EscapeDataString(_deviceId)}" +
                     $"&name={Uri.EscapeDataString(_deviceName)}" +
                     $"&ip={Uri.EscapeDataString(primaryIp)}" +
                     $"&port=8443" +
                     $"&fp={Uri.EscapeDataString(_certFingerprint)}" +
                     $"&token={Uri.EscapeDataString(_currentOneTimeToken)}";

        using var qrGenerator = new QRCodeGenerator();
        using var qrCodeData = qrGenerator.CreateQrCode(uri, QRCodeGenerator.ECCLevel.Q);
        using var qrCode = new PngByteQRCode(qrCodeData);
        byte[] qrCodeBytes = qrCode.GetGraphic(20);

        using var ms = new MemoryStream(qrCodeBytes);
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.StreamSource = ms;
        bitmap.EndInit();
        bitmap.Freeze();

        QrCodeImage.Source = bitmap;
    }

    private void CopyFingerprint_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_certFingerprint);
            MessageBox.Show("Отпечаток сертификата скопирован в буфер обмена.", "LanBridge",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch { }
    }

    private void RefreshQr_Click(object sender, RoutedEventArgs e)
    {
        GenerateNewPairingQr();
    }

    private void UnpairDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string deviceId)
        {
            _server.RemovePairedDevice(deviceId);
            RefreshDevicesList();
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }
}
