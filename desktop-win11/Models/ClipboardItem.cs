using System.Windows.Media.Imaging;

namespace LanBridge.Models;

public enum ClipboardItemType
{
    Text,
    Link,
    Image
}

public class ClipboardItem
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public ClipboardItemType Type { get; set; } = ClipboardItemType.Text;
    public string Content { get; set; } = string.Empty;
    public string? ImageBase64 { get; set; }
    public BitmapSource? ImagePreview { get; set; }
    public string Sha256 { get; set; } = string.Empty;
    public DateTime Timestamp { get; set; } = DateTime.Now;

    public string DisplayTime => Timestamp.ToString("HH:mm:ss");

    public string DisplayPreview
    {
        get
        {
            if (Type == ClipboardItemType.Image)
                return "[Скриншот / Изображение]";

            if (string.IsNullOrWhiteSpace(Content))
                return string.Empty;

            var clean = Content.Replace("\r", " ").Replace("\n", " ").Trim();
            return clean.Length > 90 ? clean.Substring(0, 90) + "…" : clean;
        }
    }

    public bool IsImage => Type == ClipboardItemType.Image;
    public bool IsLink => Type == ClipboardItemType.Link;
    public bool IsText => Type == ClipboardItemType.Text;
}
