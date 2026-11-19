using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace LanBridge.Core;

public static class CertificateManager
{
    private const string CertFileName = "cert.pfx";
    private static readonly string AppDataFolder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LanBridge");
    private const string CertPassword = "lanbridge_secure_local_pfx";

    public static (X509Certificate2 Certificate, string Fingerprint) GetOrCreateCertificate()
    {
        Directory.CreateDirectory(AppDataFolder);
        string certPath = Path.Combine(AppDataFolder, CertFileName);

        if (File.Exists(certPath))
        {
            try
            {
                var cert = new X509Certificate2(certPath, CertPassword, 
                    X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
                string fp = ComputeFingerprint(cert);
                return (cert, fp);
            }
            catch
            {
                // If corrupted, re-generate below
            }
        }

        var newCert = GenerateSelfSignedCertificate();
        byte[] pfxData = newCert.Export(X509ContentType.Pfx, CertPassword);
        File.WriteAllBytes(certPath, pfxData);

        var loadedCert = new X509Certificate2(pfxData, CertPassword, 
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
        string fingerprint = ComputeFingerprint(loadedCert);
        return (loadedCert, fingerprint);
    }

    private static X509Certificate2 GenerateSelfSignedCertificate()
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            "CN=LanBridge-PC, O=LanBridge, OU=LocalDevice",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
                critical: true));

        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection
                {
                    new Oid("1.3.6.1.5.5.7.3.1"), // Server Auth
                    new Oid("1.3.6.1.5.5.7.3.2")  // Client Auth
                },
                critical: false));

        var sanBuilder = new SubjectAlternativeNameBuilder();
        sanBuilder.AddDnsName("localhost");
        sanBuilder.AddIpAddress(IPAddress.Loopback);

        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            var ipProps = ni.GetIPProperties();
            foreach (var unicast in ipProps.UnicastAddresses)
            {
                if (unicast.Address.AddressFamily == AddressFamily.InterNetwork)
                {
                    sanBuilder.AddIpAddress(unicast.Address);
                }
            }
        }

        request.CertificateExtensions.Add(sanBuilder.Build());

        DateTimeOffset notBefore = DateTimeOffset.UtcNow.AddDays(-1);
        DateTimeOffset notAfter = DateTimeOffset.UtcNow.AddYears(10);

        var cert = request.CreateSelfSigned(notBefore, notAfter);
        return cert;
    }

    public static string ComputeFingerprint(X509Certificate2 cert)
    {
        using var sha256 = SHA256.Create();
        byte[] hash = sha256.ComputeHash(cert.RawData);
        return "SHA256:" + Convert.ToHexString(hash);
    }

    public static List<string> GetLocalIPv4Addresses()
    {
        var list = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || 
                    ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                    continue;

                foreach (var unicast in ni.GetIPProperties().UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily == AddressFamily.InterNetwork)
                    {
                        list.Add(unicast.Address.ToString());
                    }
                }
            }
        }
        catch { }

        if (list.Count == 0) list.Add("127.0.0.1");
        return list;
    }
}
