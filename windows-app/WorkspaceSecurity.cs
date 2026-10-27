using System.Security.Cryptography;
using System.Text;

namespace Cip.Windows;

internal static class WorkspaceSecurity
{
    public const int MaxFiles = 8;
    public const int MaxFileBytes = 64 * 1024;
    public const int MaxAggregateBytes = 256 * 1024;
    public const int MaxInstructionCharacters = 4000;
    public const int MaxRequestBytes = 512 * 1024;
    public const int MaxRelativePathCharacters = 240;

    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly HashSet<string> ReservedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        "COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³"
    };

    public static string ValidateWorkspaceRoot(string path)
    {
        var fullPath = Path.GetFullPath(path);
        if (!Directory.Exists(fullPath))
            throw new DirectoryNotFoundException("Çalışma alanı klasörü bulunamadı.");
        EnsureNoReparsePoints(fullPath);
        return fullPath;
    }

    public static string ValidateRelativePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Length > MaxRelativePathCharacters
            || path.Contains('\\') || path.StartsWith('/') || path.EndsWith('/'))
            throw new InvalidDataException("Dosya yolu yalnızca normalleştirilmiş, göreli / ayrımlı biçimde olabilir.");
        _ = StrictUtf8.GetByteCount(path);

        var segments = path.Split('/');
        if (segments.Any(segment => segment.Length == 0 || segment is "." or ".."
            || segment.EndsWith(' ') || segment.EndsWith('.')
            || segment.Any(character => char.IsControl(character)
                || character is '<' or '>' or ':' or '"' or '|' or '?' or '*')
            || ReservedNames.Contains(segment.Split('.')[0])))
            throw new InvalidDataException("Dosya yolu geçersiz veya Windows için ayrılmış bir ad içeriyor.");

        return path;
    }

    public static string ResolveFilePath(string workspaceRoot, string relativePath)
    {
        var root = ValidateWorkspaceRoot(workspaceRoot);
        var safeRelativePath = ValidateRelativePath(relativePath);
        var fullPath = Path.GetFullPath(Path.Combine(root, safeRelativePath.Replace('/', Path.DirectorySeparatorChar)));
        if (!IsWithinRoot(fullPath, root))
            throw new UnauthorizedAccessException("Dosya çalışma alanının dışında.");
        return fullPath;
    }

    public static void ValidateExistingWorkspaceFile(string workspaceRoot, string relativePath)
    {
        var fullPath = ResolveFilePath(workspaceRoot, relativePath);
        EnsureNoReparsePoints(fullPath);
        var info = new FileInfo(fullPath);
        if (!info.Exists || (info.Attributes & FileAttributes.Directory) != 0)
            throw new FileNotFoundException("Seçilen çalışma alanı dosyası bulunamadı.", fullPath);
    }

    public static string ReadUtf8File(string workspaceRoot, string relativePath, int maxBytes = MaxFileBytes)
    {
        ValidateExistingWorkspaceFile(workspaceRoot, relativePath);
        var fullPath = ResolveFilePath(workspaceRoot, relativePath);
        return StrictUtf8.GetString(ReadBoundedBytes(fullPath, maxBytes));
    }

    public static IReadOnlyList<WorkspacePlanFile> ReadSelectedFiles(
        string workspaceRoot, IEnumerable<string> relativePaths)
    {
        var paths = relativePaths.ToList();
        if (paths.Count is < 1 or > MaxFiles)
            throw new InvalidOperationException($"Plan için 1 ile {MaxFiles} arasında dosya seçin.");

        var uniquePaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var files = new List<WorkspacePlanFile>(paths.Count);
        var aggregateBytes = 0;
        foreach (var path in paths)
        {
            ValidateRelativePath(path);
            if (!uniquePaths.Add(path))
                throw new InvalidDataException("Aynı dosya birden fazla kez seçilemez.");
            var content = ReadUtf8File(workspaceRoot, path);
            var byteCount = StrictUtf8.GetByteCount(content);
            if (content.Contains('\0'))
                throw new InvalidDataException("İkili veya NUL içeren dosyalar planlamaya gönderilemez.");
            aggregateBytes += byteCount;
            if (aggregateBytes > MaxAggregateBytes)
                throw new InvalidDataException("Seçili dosyaların toplamı 256 KiB sınırını aşıyor.");
            files.Add(new WorkspacePlanFile { path = path, content = content });
        }

        return files;
    }

    public static WorkspacePlanResponse ValidatePlanResponse(
        WorkspacePlanResponse response, IReadOnlyList<WorkspacePlanFile> submittedFiles)
    {
        if (string.IsNullOrWhiteSpace(response.summary) || response.summary.Length > 2000
            || response.summary.Contains('\0'))
            throw new InvalidDataException("Plan özeti boş veya 2.000 karakter sınırını aşıyor.");
        _ = StrictUtf8.GetByteCount(response.summary);
        if (response.changes is null || response.changes.Count != submittedFiles.Count)
            throw new InvalidDataException("Plan yanıtı seçilen dosyaların tamamını tam olarak bir kez içermelidir.");

        var submitted = submittedFiles.Select(file => file.path).ToHashSet(StringComparer.Ordinal);
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var totalBytes = 0;
        foreach (var change in response.changes)
        {
            if (change is null)
                throw new InvalidDataException("Plan yanıtında boş bir değişiklik var.");
            ValidateRelativePath(change.relative_path);
            if (!submitted.Contains(change.relative_path) || !seen.Add(change.relative_path))
                throw new InvalidDataException("Plan yanıtında seçilmemiş veya yinelenen bir dosya yolu var.");
            if (change.replacement_content is null || change.replacement_content.Contains('\0'))
                throw new InvalidDataException("Plan yanıtı geçersiz dosya içeriği içeriyor.");
            var size = StrictUtf8.GetByteCount(change.replacement_content);
            if (size > MaxFileBytes)
                throw new InvalidDataException("Planlanan dosya 64 KiB sınırını aşıyor.");
            totalBytes += size;
            if (totalBytes > MaxAggregateBytes)
                throw new InvalidDataException("Planlanan dosyaların toplamı 256 KiB sınırını aşıyor.");
        }
        return response;
    }

    public static WorkspacePlanRequest ValidatePlanRequest(WorkspacePlanRequest request)
    {
        request.instruction = ValidateInstruction(request.instruction);
        if (request.files is null || request.files.Count is < 1 or > MaxFiles)
            throw new InvalidOperationException($"Plan için 1 ile {MaxFiles} arasında dosya seçin.");

        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var totalBytes = 0;
        foreach (var file in request.files)
        {
            if (file is null)
                throw new InvalidDataException("Plan isteğinde boş dosya girdisi var.");
            ValidateRelativePath(file.path);
            if (!seen.Add(file.path))
                throw new InvalidDataException("Aynı dosya birden fazla kez seçilemez.");
            if (file.content is null || file.content.Contains('\0'))
                throw new InvalidDataException("Planlama dosyası geçersiz içerik içeriyor.");
            var size = StrictUtf8.GetByteCount(file.content);
            if (size > MaxFileBytes)
                throw new InvalidDataException("Seçili dosyalardan biri 64 KiB sınırını aşıyor.");
            totalBytes += size;
            if (totalBytes > MaxAggregateBytes)
                throw new InvalidDataException("Seçili dosyaların toplamı 256 KiB sınırını aşıyor.");
        }
        return request;
    }

    public static string ValidateInstruction(string instruction)
    {
        if (string.IsNullOrWhiteSpace(instruction) || instruction.Length > MaxInstructionCharacters
            || instruction.Any(character => character == '\0'))
            throw new ArgumentException($"Talimat boş olamaz ve {MaxInstructionCharacters} karakteri aşamaz.");
        _ = StrictUtf8.GetByteCount(instruction);
        return instruction;
    }

    public static void ValidateServerProfile(ServerProfile profile, string workspaceRoot)
    {
        var root = ValidateWorkspaceRoot(workspaceRoot);
        if (!string.Equals(ValidateWorkspaceRoot(profile.WorkspaceRoot), root, StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("Profilin çalışma alanı seçili çalışma alanıyla eşleşmiyor.");
        if (string.IsNullOrWhiteSpace(profile.Name) || profile.Name.Trim().Length > 50
            || profile.Name.Any(char.IsControl))
            throw new ArgumentException("Sunucu profili için 1-50 karakterlik bir ad girin.");
        if (profile.Arguments.Length > 4096 || profile.Arguments.Any(char.IsControl))
            throw new ArgumentException("Başlatma bağımsız değişkenleri geçersiz veya çok uzun.");

        var executable = Path.GetFullPath(profile.ExecutablePath);
        var workingDirectory = Path.GetFullPath(profile.WorkingDirectory);
        if (!string.Equals(Path.GetExtension(executable), ".exe", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Profil yalnızca açıkça seçilmiş bir Windows .exe dosyasını başlatabilir.");
        if (IsShellExecutable(Path.GetFileName(executable)))
            throw new InvalidOperationException("Komut kabukları profil sunucusu olarak kullanılamaz.");
        if (!IsWithinRoot(workingDirectory, root))
            throw new UnauthorizedAccessException("Çalışma klasörü seçili çalışma alanının içinde olmalıdır.");
        EnsureNoReparsePoints(executable);
        EnsureNoReparsePoints(workingDirectory);
        if (!File.Exists(executable) || !Directory.Exists(workingDirectory))
            throw new FileNotFoundException("Profilin çalıştırılabilir dosyası veya çalışma klasörü bulunamadı.");
    }

    public static void WriteReplacementAtomically(
        string workspaceRoot, string relativePath, string expectedOriginalContent, string replacementContent)
    {
        ValidateExistingWorkspaceFile(workspaceRoot, relativePath);
        if (replacementContent.Contains('\0'))
            throw new InvalidDataException("Planlanan içerik NUL karakteri içeriyor.");
        var originalBytes = StrictUtf8.GetBytes(expectedOriginalContent);
        var replacementBytes = StrictUtf8.GetBytes(replacementContent);
        if (originalBytes.Length > MaxFileBytes || replacementBytes.Length > MaxFileBytes)
            throw new InvalidDataException("Dosya 64 KiB sınırını aşıyor.");

        var fullPath = ResolveFilePath(workspaceRoot, relativePath);
        var actualBytes = ReadBoundedBytes(fullPath, MaxFileBytes);
        if (!MatchesOriginal(actualBytes, originalBytes))
            throw new IOException($"“{relativePath}” plan oluşturulduktan sonra değişti; yeni plan oluşturun.");

        var tempPath = Path.Combine(Path.GetDirectoryName(fullPath)!, $".cip-{Guid.NewGuid():N}.tmp");
        var tempCreated = false;
        try
        {
            using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                tempCreated = true;
                stream.Write(replacementBytes);
                stream.Flush(flushToDisk: true);
            }
            EnsureNoReparsePoints(fullPath);
            if (!File.Exists(fullPath) || !MatchesOriginal(ReadBoundedBytes(fullPath, MaxFileBytes), originalBytes))
                throw new IOException($"“{relativePath}” yazma öncesi değişti; mevcut içerik korundu.");
            File.Move(tempPath, fullPath, overwrite: true);
        }
        finally
        {
            if (tempCreated && File.Exists(tempPath))
                File.Delete(tempPath);
        }
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
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Sembolik bağlantı veya yeniden ayrıştırma noktası üzerinden işlem engellendi.");
        }
    }

    private static bool IsWithinRoot(string path, string root)
    {
        var fullPath = Path.GetFullPath(path);
        var fullRoot = Path.GetFullPath(root);
        var pathRoot = Path.GetPathRoot(fullRoot)!;
        if (fullRoot.Length > pathRoot.Length)
            fullRoot = fullRoot.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (string.Equals(fullPath, fullRoot, StringComparison.OrdinalIgnoreCase))
            return true;
        var relative = Path.GetRelativePath(fullRoot, fullPath);
        return !Path.IsPathRooted(relative)
            && relative != ".."
            && !relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal)
            && !relative.StartsWith(".." + Path.AltDirectorySeparatorChar, StringComparison.Ordinal);
    }

    private static bool IsShellExecutable(string name) =>
        new[]
        {
            "cmd.exe", "powershell.exe", "pwsh.exe", "wscript.exe", "cscript.exe",
            "mshta.exe", "bash.exe", "sh.exe", "wsl.exe"
        }
            .Contains(name, StringComparer.OrdinalIgnoreCase);

    private static bool MatchesOriginal(byte[] actual, byte[] original) =>
        actual.Length == original.Length
        && CryptographicOperations.FixedTimeEquals(SHA256.HashData(actual), SHA256.HashData(original));

    private static byte[] ReadBoundedBytes(string path, int maxBytes)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            4096, FileOptions.SequentialScan);
        if (stream.Length > maxBytes)
            throw new InvalidDataException($"Dosya {maxBytes / 1024} KiB sınırını aşıyor.");
        var bytes = new byte[(int)stream.Length];
        stream.ReadExactly(bytes);
        if (stream.ReadByte() != -1)
            throw new InvalidDataException($"Dosya {maxBytes / 1024} KiB sınırını aşıyor.");
        return bytes;
    }
}
