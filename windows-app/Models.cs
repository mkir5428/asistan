namespace Cip.Windows;

internal sealed class AppState
{
    public string ServerUrl { get; set; } = "";
    public string PairingId { get; set; } = "";
    public string PcToken { get; set; } = "";
    public string PairingCode { get; set; } = "";
    public long PairingCodeExpiresAt { get; set; }
    public List<string> AllowedFolders { get; set; } = [];
    public Dictionary<string, string> SourceFilesByAction { get; set; } = [];
}

internal sealed class PairingCreated
{
    public string pairing_id { get; set; } = "";
    public string pairing_code { get; set; } = "";
    public string pc_token { get; set; } = "";
    public int pairing_code_expires_in { get; set; }
    public int pairing_expires_in { get; set; }
}

internal sealed class TransferAction
{
    public string action_id { get; set; } = "";
    public string direction { get; set; } = "";
    public string filename { get; set; } = "";
    public long size { get; set; }
    public string sha256 { get; set; } = "";
    public string state { get; set; } = "";
    public long expires_at { get; set; }
    public string? source_path { get; set; }
}
