using System.Security.Cryptography;

namespace Cip.Windows;

internal static class TransferSecurity
{
    public const long MaxFileBytes = 20L * 1024 * 1024;
    public const int MaxActiveActions = 2;

    public static string ValidateServerUrl(string value)
    {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps
            || string.IsNullOrWhiteSpace(uri.Host)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment))
        {
            throw new ArgumentException("Sunucu adresi yalnızca https:// biçiminde geçerli bir adres olabilir.");
        }

        return uri.ToString().TrimEnd('/');
    }

    public static bool IsPathAllowed(string path, IEnumerable<string> allowedFolders)
    {
        var fullPath = Path.GetFullPath(path);
        foreach (var allowedFolder in allowedFolders)
        {
            var root = Path.GetFullPath(allowedFolder);
            var rootWithoutTrailingSeparator = root.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (rootWithoutTrailingSeparator.Length >= Path.GetPathRoot(root)!.Length)
                root = rootWithoutTrailingSeparator;
            if (string.Equals(fullPath, root, StringComparison.OrdinalIgnoreCase))
                return true;

            var relative = Path.GetRelativePath(root, fullPath);
            if (!Path.IsPathRooted(relative)
                && relative != ".."
                && !relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal)
                && !relative.StartsWith(".." + Path.AltDirectorySeparatorChar, StringComparison.Ordinal))
                return true;
        }

        return false;
    }

    public static void EnsureNoReparsePoints(string path)
    {
        var fullPath = Path.GetFullPath(path);
        var root = Path.GetPathRoot(fullPath)
            ?? throw new IOException("Dosya yolu geçersiz.");
        var current = root;
        foreach (var segment in fullPath[root.Length..].Split(
                     [Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar],
                     StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, segment);
            if (!File.Exists(current) && !Directory.Exists(current))
                continue;

            var attributes = File.GetAttributes(current);
            if ((attributes & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Sembolik bağlantı veya yeniden ayrıştırma noktası üzerinden dosya aktarımı engellendi.");
        }
    }

    public static void ValidateTransferFile(string path, IEnumerable<string> allowedFolders)
    {
        if (!IsPathAllowed(path, allowedFolders))
            throw new UnauthorizedAccessException("Dosya izin verilen klasörlerin dışında.");
        EnsureNoReparsePoints(path);
        var info = new FileInfo(path);
        if (!info.Exists)
            throw new FileNotFoundException("Dosya bulunamadı.", path);
        if (info.Length > MaxFileBytes)
            throw new InvalidOperationException("Dosya 20 MiB sınırını aşıyor.");
    }

    public static string ValidateRemoteFilename(string filename)
    {
        if (string.IsNullOrWhiteSpace(filename)
            || filename is "." or ".."
            || filename.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0
            || filename.Contains('/') || filename.Contains('\\')
            || filename.EndsWith(' ') || filename.EndsWith('.')
            || filename.Length > 180)
            throw new InvalidDataException("Sunucudan gelen dosya adı güvenli değil.");

        var stem = filename.Split('.')[0];
        if (new[] { "CON", "PRN", "AUX", "NUL" }.Contains(stem, StringComparer.OrdinalIgnoreCase)
            || (stem.Length == 4 && (stem.StartsWith("COM", StringComparison.OrdinalIgnoreCase)
                || stem.StartsWith("LPT", StringComparison.OrdinalIgnoreCase))
                && stem[3] is >= '1' and <= '9'))
            throw new InvalidDataException("Sunucudan gelen dosya adı Windows için ayrılmış bir addır.");

        return filename;
    }

    public static async Task<(long Size, string Sha256)> HashFileAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (stream.Length > MaxFileBytes)
            throw new InvalidOperationException("Dosya 20 MiB sınırını aşıyor.");
        var hash = await SHA256.HashDataAsync(stream, cancellationToken);
        return (stream.Length, Convert.ToHexString(hash).ToLowerInvariant());
    }
}
