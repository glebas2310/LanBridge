using System.Security.Cryptography;
using System.Text;

namespace LanBridge.Core;

public class EchoLoopGuard
{
    private readonly object _lock = new();
    private string _lastRemoteSha256 = string.Empty;
    private readonly HashSet<string> _recentRemoteHashes = new();
    private readonly Queue<string> _hashQueue = new();
    private const int MaxTracked = 50;

    public void RecordRemote(string sha256)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return;

        lock (_lock)
        {
            _lastRemoteSha256 = sha256;
            if (_recentRemoteHashes.Add(sha256))
            {
                _hashQueue.Enqueue(sha256);
                if (_hashQueue.Count > MaxTracked)
                {
                    string old = _hashQueue.Dequeue();
                    _recentRemoteHashes.Remove(old);
                }
            }
        }
    }

    public bool IsLoop(string sha256)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return false;

        lock (_lock)
        {
            return _lastRemoteSha256.Equals(sha256, StringComparison.OrdinalIgnoreCase) ||
                   _recentRemoteHashes.Contains(sha256);
        }
    }

    public static string ComputeSha256(string text)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        return ComputeSha256(bytes);
    }

    public static string ComputeSha256(byte[] data)
    {
        using var sha = SHA256.Create();
        byte[] hash = sha.ComputeHash(data);
        return Convert.ToHexString(hash);
    }
}
