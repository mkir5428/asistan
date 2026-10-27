using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Cip.Windows;

internal sealed class LocalStateStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Cip.Windows.Transfer.v1");
    private readonly string path = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "CipWindows", "transfer-settings.bin");

    public AppState Load()
    {
        if (!File.Exists(path))
            return new AppState();

        try
        {
            var protectedBytes = File.ReadAllBytes(path);
            var json = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<AppState>(json) ?? new AppState();
        }
        catch (Exception error) when (error is CryptographicException or JsonException or IOException)
        {
            throw new InvalidOperationException(
                "Kaydedilmiş aktarım ayarları bu Windows hesabında açılamadı. Dosyayı silip bağlantıyı yeniden kurun.", error);
        }
    }

    public void Save(AppState state)
    {
        var directory = Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(directory);
        var json = JsonSerializer.SerializeToUtf8Bytes(state);
        var protectedBytes = ProtectedData.Protect(json, Entropy, DataProtectionScope.CurrentUser);
        var temporaryPath = path + ".tmp";
        File.WriteAllBytes(temporaryPath, protectedBytes);
        File.Move(temporaryPath, path, overwrite: true);
    }
}
