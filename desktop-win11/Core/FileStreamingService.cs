using System.IO;
using System.Security.Cryptography;

namespace LanBridge.Core;

public class FileStreamingService
{
    public const int ChunkSize = 64 * 1024; // 64 KB chunks
    private static readonly string DownloadFolder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LanBridge");

    public event Action<string, long, long>? OnProgress;
    public event Action<string, string>? OnFileReceived; // fileId, localFilePath

    public FileStreamingService()
    {
        Directory.CreateDirectory(DownloadFolder);
    }

    public static string GetDownloadFolderPath() => DownloadFolder;

    public async Task<string> ReceiveFileStreamAsync(
        string fileId, 
        string fileName, 
        long totalBytes, 
        string expectedSha256, 
        Stream networkStream, 
        CancellationToken ct)
    {
        Directory.CreateDirectory(DownloadFolder);
        string safeFileName = Path.GetFileName(fileName);
        string targetPath = Path.Combine(DownloadFolder, safeFileName);

        // Avoid overwriting existing files
        int counter = 1;
        while (File.Exists(targetPath))
        {
            string nameWithoutExt = Path.GetFileNameWithoutExtension(safeFileName);
            string ext = Path.GetExtension(safeFileName);
            targetPath = Path.Combine(DownloadFolder, $"{nameWithoutExt} ({counter++}){ext}");
        }

        byte[] buffer = new byte[ChunkSize];
        long bytesReceived = 0;

        using (var sha256 = SHA256.Create())
        using (var fileStream = new FileStream(targetPath, FileMode.Create, FileAccess.Write, FileShare.None, ChunkSize, useAsync: true))
        {
            while (bytesReceived < totalBytes)
            {
                int toRead = (int)Math.Min(ChunkSize, totalBytes - bytesReceived);
                int read = await networkStream.ReadAsync(buffer.AsMemory(0, toRead), ct);
                if (read == 0)
                {
                    throw new EndOfStreamException($"Stream closed prematurely after {bytesReceived}/{totalBytes} bytes");
                }

                await fileStream.WriteAsync(buffer.AsMemory(0, read), ct);
                sha256.TransformBlock(buffer, 0, read, null, 0);

                bytesReceived += read;
                OnProgress?.Invoke(fileId, bytesReceived, totalBytes);
            }

            sha256.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
            string actualSha256 = Convert.ToHexString(sha256.Hash ?? Array.Empty<byte>());

            if (!string.IsNullOrEmpty(expectedSha256) &&
                !actualSha256.Equals(expectedSha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(targetPath);
                throw new InvalidDataException($"SHA-256 mismatch! Expected {expectedSha256}, got {actualSha256}");
            }
        }

        OnFileReceived?.Invoke(fileId, targetPath);
        return targetPath;
    }

    public async Task<(string Sha256, long FileSize)> SendFileStreamAsync(
        string filePath, 
        string fileId, 
        Stream networkStream, 
        CancellationToken ct)
    {
        if (!File.Exists(filePath))
            throw new FileNotFoundException("File not found", filePath);

        var fileInfo = new FileInfo(filePath);
        long totalBytes = fileInfo.Length;
        long bytesSent = 0;
        byte[] buffer = new byte[ChunkSize];

        using var sha256 = SHA256.Create();
        using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, ChunkSize, useAsync: true);

        int read;
        while ((read = await fileStream.ReadAsync(buffer.AsMemory(0, ChunkSize), ct)) > 0)
        {
            await networkStream.WriteAsync(buffer.AsMemory(0, read), ct);
            sha256.TransformBlock(buffer, 0, read, null, 0);

            bytesSent += read;
            OnProgress?.Invoke(fileId, bytesSent, totalBytes);
        }

        sha256.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
        string calculatedSha256 = Convert.ToHexString(sha256.Hash ?? Array.Empty<byte>());

        return (calculatedSha256, totalBytes);
    }

    public static string ComputeFileSha256(string filePath)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(filePath);
        byte[] hash = sha.ComputeHash(stream);
        return Convert.ToHexString(hash);
    }
}
