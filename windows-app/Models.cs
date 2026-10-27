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
    public string PlanningApiKey { get; set; } = "";
    public string PlanningServerUrl { get; set; } = "";
    public string WorkspaceRoot { get; set; } = "";
    public List<string> WorkspaceFiles { get; set; } = [];
    public List<ServerProfile> ServerProfiles { get; set; } = [];
}

internal sealed class ServerProfile
{
    public string Name { get; set; } = "";
    public string WorkspaceRoot { get; set; } = "";
    public string ExecutablePath { get; set; } = "";
    public string WorkingDirectory { get; set; } = "";
    public string Arguments { get; set; } = "";
}

internal sealed class WorkspacePlanFile
{
    public string path { get; set; } = "";
    public string content { get; set; } = "";
}

internal sealed class WorkspacePlanRequest
{
    public string instruction { get; set; } = "";
    public List<WorkspacePlanFile> files { get; set; } = [];
}

internal sealed class WorkspacePlanChange
{
    public string relative_path { get; set; } = "";
    public string replacement_content { get; set; } = "";
}

internal sealed class WorkspacePlanResponse
{
    public string summary { get; set; } = "";
    public List<WorkspacePlanChange> changes { get; set; } = [];
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
