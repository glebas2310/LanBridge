using System.IO;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using LanBridge.Models;

namespace LanBridge.Core;

public class Win32ClipboardListener : IDisposable
{
    private HwndSource? _hwndSource;
    private IntPtr _hwnd = IntPtr.Zero;
    private readonly EchoLoopGuard _loopGuard;

    public event Action<ClipboardItem>? OnClipboardCaptured;

    public Win32ClipboardListener(EchoLoopGuard loopGuard)
    {
        _loopGuard = loopGuard;
    }

    public void Hook(Window window)
    {
        var helper = new WindowInteropHelper(window);
        _hwnd = helper.EnsureHandle();
        _hwndSource = HwndSource.FromHwnd(_hwnd);
        _hwndSource?.AddHook(WndProc);
        Win32Interop.AddClipboardFormatListener(_hwnd);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == Win32Interop.WM_CLIPBOARDUPDATE)
        {
            ProcessClipboardUpdate();
            handled = true;
        }
        return IntPtr.Zero;
    }

    private void ProcessClipboardUpdate()
    {
        // Must read clipboard on STA UI thread
        Application.Current.Dispatcher.Invoke(() =>
        {
            try
            {
                if (Clipboard.ContainsImage())
                {
                    var bitmapSource = Clipboard.GetImage();
                    if (bitmapSource != null)
                    {
                        byte[] pngBytes = ConvertBitmapToPng(bitmapSource);
                        string sha256 = EchoLoopGuard.ComputeSha256(pngBytes);

                        if (_loopGuard.IsLoop(sha256))
                            return;

                        string base64 = Convert.ToBase64String(pngBytes);
                        var item = new ClipboardItem
                        {
                            Type = ClipboardItemType.Image,
                            Content = $"[Screenshot: {bitmapSource.PixelWidth}x{bitmapSource.PixelHeight} PNG]",
                            ImageBase64 = base64,
                            ImagePreview = bitmapSource,
                            Sha256 = sha256,
                            Timestamp = DateTime.Now
                        };

                        OnClipboardCaptured?.Invoke(item);
                        return;
                    }
                }

                if (Clipboard.ContainsText())
                {
                    string text = Clipboard.GetText();
                    if (string.IsNullOrEmpty(text)) return;

                    string sha256 = EchoLoopGuard.ComputeSha256(text);
                    if (_loopGuard.IsLoop(sha256))
                        return;

                    bool isLink = Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) &&
                                  (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps);

                    var item = new ClipboardItem
                    {
                        Type = isLink ? ClipboardItemType.Link : ClipboardItemType.Text,
                        Content = text,
                        Sha256 = sha256,
                        Timestamp = DateTime.Now
                    };

                    OnClipboardCaptured?.Invoke(item);
                }
            }
            catch { }
        });
    }

    public void SetLocalClipboardText(string text, string sha256)
    {
        _loopGuard.RecordRemote(sha256);
        Application.Current.Dispatcher.Invoke(() =>
        {
            try
            {
                Clipboard.SetText(text);
            }
            catch { }
        });
    }

    public void SetLocalClipboardImage(byte[] pngBytes, string sha256)
    {
        _loopGuard.RecordRemote(sha256);
        Application.Current.Dispatcher.Invoke(() =>
        {
            try
            {
                using var ms = new MemoryStream(pngBytes);
                var bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = ms;
                bitmap.EndInit();
                bitmap.Freeze();

                Clipboard.SetImage(bitmap);
            }
            catch { }
        });
    }

    public static byte[] ConvertBitmapToPng(BitmapSource source)
    {
        using var ms = new MemoryStream();
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));
        encoder.Save(ms);
        return ms.ToArray();
    }

    public void Dispose()
    {
        if (_hwnd != IntPtr.Zero)
        {
            Win32Interop.RemoveClipboardFormatListener(_hwnd);
            _hwndSource?.RemoveHook(WndProc);
            _hwnd = IntPtr.Zero;
        }
    }
}
