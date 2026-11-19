using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using LanBridge.Core;
using LanBridge.Models;
using LanBridge.UI;
using DragEventArgs = System.Windows.DragEventArgs;
using DataFormats = System.Windows.DataFormats;
using DragDropEffects = System.Windows.DragDropEffects;

namespace LanBridge;

public partial class MainWindow : Window
{
    private readonly EchoLoopGuard _loopGuard = new();
    private readonly FileStreamingService _fileStreaming = new();
    private readonly LanBridgeServer _server;
    private readonly UdpDiscoveryBroadcaster _discovery;
    private readonly Win32ClipboardListener _clipboardListener;

    private readonly string _deviceId;
    private readonly string _deviceName;
    private readonly string _certFingerprint;

    public ObservableCollection<ClipboardItem> History { get; } = new();
    private const int MaxHistory = 25;

    public MainWindow()
    {
        InitializeComponent();

        _deviceId = "win_" + Environment.MachineName.ToLowerInvariant().Replace("-", "_");
        _deviceName = Environment.MachineName;

        // 1. Certificate setup
        var (cert, fingerprint) = CertificateManager.GetOrCreateCertificate();
        _certFingerprint = fingerprint;

        // 2. Server setup
        _server = new LanBridgeServer(8443, cert, _deviceId, _deviceName, _fileStreaming);
        _server.OnDeviceConnected += Server_OnDeviceConnected;
        _server.OnDeviceDisconnected += Server_OnDeviceDisconnected;
        _server.OnPingUpdated += Server_OnPingUpdated;
        _server.OnRemoteClipboardText += Server_OnRemoteClipboardText;
        _server.OnRemoteClipboardImage += Server_OnRemoteClipboardImage;
        _server.OnRemoteFileOffer += Server_OnRemoteFileOffer;

        _fileStreaming.OnProgress += FileStreaming_OnProgress;
        _fileStreaming.OnFileReceived += FileStreaming_OnFileReceived;

        // 3. UDP Discovery Broadcaster
        _discovery = new UdpDiscoveryBroadcaster(_deviceId, _deviceName, 8443, _certFingerprint);

        // 4. Win32 Clipboard Listener
        _clipboardListener = new Win32ClipboardListener(_loopGuard);
        _clipboardListener.OnClipboardCaptured += ClipboardListener_OnClipboardCaptured;

        HistoryItemsControl.ItemsSource = History;

        Loaded += MainWindow_Loaded;
        Closing += MainWindow_Closing;
    }

    private void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        _clipboardListener.Hook(this);
        _server.Start();
        _discovery.Start();

        StartupCheckBox.IsChecked = Win32Interop.IsRunOnStartupEnabled();
        PositionWindowAboveTaskbar();
    }

    private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        e.Cancel = true;
        Hide();
    }

    public void ToggleFlyoutVisibility()
    {
        if (IsVisible)
        {
            Hide();
        }
        else
        {
            PositionWindowAboveTaskbar();
            Show();
            Activate();
        }
    }

    private void PositionWindowAboveTaskbar()
    {
        var workArea = SystemParameters.WorkArea;
        Left = workArea.Right - Width - 12;
        Top = workArea.Bottom - Height - 12;
    }

    // --- Clipboard handling ---

    private void ClipboardListener_OnClipboardCaptured(ClipboardItem item)
    {
        Dispatcher.Invoke(() =>
        {
            AddHistoryItem(item);

            if (AutoSyncToggle.IsChecked == true && _server.IsClientConnected)
            {
                if (item.Type == ClipboardItemType.Image && !string.IsNullOrEmpty(item.ImageBase64))
                {
                    var msg = new ClipboardImageMessage
                    {
                        Width = item.ImagePreview?.PixelWidth ?? 0,
                        Height = item.ImagePreview?.PixelHeight ?? 0,
                        DataBase64 = item.ImageBase64,
                        Sha256 = item.Sha256
                    };
                    _ = _server.SendJsonMessageAsync(msg);
                }
                else if (item.Type == ClipboardItemType.Text || item.Type == ClipboardItemType.Link)
                {
                    var msg = new ClipboardTextMessage
                    {
                        Content = item.Content,
                        Sha256 = item.Sha256
                    };
                    _ = _server.SendJsonMessageAsync(msg);
                }
            }
        });
    }

    private void Server_OnRemoteClipboardText(ClipboardTextMessage msg)
    {
        if (_loopGuard.IsLoop(msg.Sha256)) return;

        Dispatcher.Invoke(() =>
        {
            _clipboardListener.SetLocalClipboardText(msg.Content, msg.Sha256);

            bool isLink = Uri.TryCreate(msg.Content.Trim(), UriKind.Absolute, out var uri) &&
                          (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps);

            var item = new ClipboardItem
            {
                Type = isLink ? ClipboardItemType.Link : ClipboardItemType.Text,
                Content = msg.Content,
                Sha256 = msg.Sha256,
                Timestamp = DateTime.Now
            };

            AddHistoryItem(item);
        });
    }

    private void Server_OnRemoteClipboardImage(ClipboardImageMessage msg)
    {
        if (_loopGuard.IsLoop(msg.Sha256)) return;

        Dispatcher.Invoke(() =>
        {
            try
            {
                byte[] pngBytes = Convert.FromBase64String(msg.DataBase64);
                _clipboardListener.SetLocalClipboardImage(pngBytes, msg.Sha256);

                using var ms = new MemoryStream(pngBytes);
                var bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = ms;
                bitmap.EndInit();
                bitmap.Freeze();

                var item = new ClipboardItem
                {
                    Type = ClipboardItemType.Image,
                    Content = $"[Скриншот: {msg.Width}x{msg.Height} PNG]",
                    ImageBase64 = msg.DataBase64,
                    ImagePreview = bitmap,
                    Sha256 = msg.Sha256,
                    Timestamp = DateTime.Now
                };

                AddHistoryItem(item);
            }
            catch { }
        });
    }

    private void AddHistoryItem(ClipboardItem item)
    {
        History.Insert(0, item);
        while (History.Count > MaxHistory)
        {
            History.RemoveAt(History.Count - 1);
        }
    }

    // --- Server & Connection State Events ---

    private void Server_OnDeviceConnected(PairedDevice device)
    {
        Dispatcher.Invoke(() =>
        {
            StatusDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#2EA043"));
            DeviceNameText.Text = device.Name;
            DeviceDetailsText.Text = $"IP: {device.Ip}";
            PingBadge.Visibility = Visibility.Visible;
            PingText.Text = "Подключен";
        });
    }

    private void Server_OnDeviceDisconnected(string deviceId)
    {
        Dispatcher.Invoke(() =>
        {
            StatusDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#8B949E"));
            DeviceNameText.Text = "Смартфон не подключен";
            DeviceDetailsText.Text = "Ожидание соединения в локальной сети…";
            PingBadge.Visibility = Visibility.Collapsed;
        });
    }

    private void Server_OnPingUpdated(long pingMs)
    {
        Dispatcher.Invoke(() =>
        {
            PingText.Text = $"{pingMs} ms";
        });
    }

    private void Server_OnRemoteFileOffer(FileOfferMessage offer)
    {
        Dispatcher.Invoke(() =>
        {
            TransferProgressPanel.Visibility = Visibility.Visible;
            TransferProgressBar.Value = 0;
            TransferStatusText.Text = $"Прием файла: {offer.FileName} ({FormatFileSize(offer.FileSize)})…";
        });
    }

    private void FileStreaming_OnProgress(string fileId, long current, long total)
    {
        Dispatcher.Invoke(() =>
        {
            TransferProgressPanel.Visibility = Visibility.Visible;
            int percent = total > 0 ? (int)((current * 100) / total) : 0;
            TransferProgressBar.Value = percent;
            TransferStatusText.Text = $"Передача: {percent}% ({FormatFileSize(current)} / {FormatFileSize(total)})";
        });
    }

    private void FileStreaming_OnFileReceived(string fileId, string localPath)
    {
        Dispatcher.Invoke(() =>
        {
            TransferProgressPanel.Visibility = Visibility.Collapsed;
            string fileName = Path.GetFileName(localPath);
            App.ShowNotification("Файл получен", $"Сохранен в Downloads\\LanBridge: {fileName}");
        });
    }

    // --- Drag & Drop Zone ---

    private void DropZone_DragEnter(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            e.Effects = DragDropEffects.Copy;
            DropZoneBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#1F2837"));
        }
    }

    private void DropZone_DragLeave(object sender, DragEventArgs e)
    {
        DropZoneBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#161920"));
    }

    private async void DropZone_Drop(object sender, DragEventArgs e)
    {
        DropZoneBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#161920"));

        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        string[] files = (string[])e.Data.GetData(DataFormats.FileDrop);
        if (files == null || files.Length == 0) return;

        if (!_server.IsClientConnected)
        {
            MessageBox.Show("Смартфон не подключен. Подключите устройство через QR-код перед отправкой файлов.",
                "LanBridge", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        foreach (var file in files)
        {
            if (File.Exists(file))
            {
                await SendFileToPhoneAsync(file);
            }
        }
    }

    private async Task SendFileToPhoneAsync(string filePath)
    {
        try
        {
            var fileInfo = new FileInfo(filePath);
            string fileId = Guid.NewGuid().ToString("N");
            string sha256 = FileStreamingService.ComputeFileSha256(filePath);

            var offer = new FileOfferMessage
            {
                FileId = fileId,
                FileName = fileInfo.Name,
                FileSize = fileInfo.Length,
                Sha256 = sha256
            };

            _server.RegisterOutgoingFile(fileId, filePath, offer);

            TransferProgressPanel.Visibility = Visibility.Visible;
            TransferProgressBar.Value = 0;
            TransferStatusText.Text = $"Отправка предложения: {fileInfo.Name}…";

            await _server.SendJsonMessageAsync(offer);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Ошибка отправки файла: {ex.Message}", "LanBridge",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private static string FormatFileSize(long bytes)
    {
        if (bytes < 1024) return $"{bytes} Б";
        if (bytes < 1024 * 1024) return $"{(bytes / 1024.0):F1} КБ";
        if (bytes < 1024 * 1024 * 1024) return $"{(bytes / (1024.0 * 1024.0)):F1} МБ";
        return $"{(bytes / (1024.0 * 1024.0 * 1024.0)):F2} ГБ";
    }

    // --- UI Actions ---

    private void OpenPairing_Click(object sender, RoutedEventArgs e)
    {
        var pairingWindow = new PairingWindow(_server, _deviceId, _deviceName, _certFingerprint)
        {
            Owner = this
        };
        pairingWindow.ShowDialog();
    }

    private void CloseFlyout_Click(object sender, RoutedEventArgs e)
    {
        Hide();
    }

    private void CopyHistoryItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is ClipboardItem item)
        {
            if (item.Type == ClipboardItemType.Image && item.ImagePreview != null)
            {
                _loopGuard.RecordRemote(item.Sha256);
                Clipboard.SetImage(item.ImagePreview);
            }
            else
            {
                _loopGuard.RecordRemote(item.Sha256);
                Clipboard.SetText(item.Content);
            }
        }
    }

    private void ClearHistory_Click(object sender, RoutedEventArgs e)
    {
        History.Clear();
    }

    private void AutoSyncToggle_Changed(object sender, RoutedEventArgs e)
    {
        // Handled via AutoSyncToggle.IsChecked check
    }

    private void StartupCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        Win32Interop.SetRunOnStartup(StartupCheckBox.IsChecked == true);
    }

    private void OpenDownloads_Click(object sender, RoutedEventArgs e)
    {
        string folder = FileStreamingService.GetDownloadFolderPath();
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = folder,
                UseShellExecute = true
            });
        }
        catch { }
    }
}
