using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Cip.Windows;

internal sealed class AgentAnalysisAgent
{
    public string id { get; set; } = "";
    public string name { get; set; } = "";
    public string prompt { get; set; } = "";
}

internal sealed class AgentAnalysisRequest
{
    public string instruction { get; set; } = "";
    public bool consent { get; set; }
    public List<AgentAnalysisAgent> agents { get; set; } = [];
}

internal sealed class AgentAnalysisSelectedAgent
{
    public string id { get; set; } = "";
    public string name { get; set; } = "";
}

internal sealed class AgentAnalysisResult
{
    public string agent_id { get; set; } = "";
    public string analysis { get; set; } = "";
}

internal sealed class AgentAnalysisResponse
{
    public List<AgentAnalysisSelectedAgent> selected_agents { get; set; } = [];
    public List<AgentAnalysisResult> analyses { get; set; } = [];
    public string synthesis { get; set; } = "";
}

internal static class AgentAnalysisSecurity
{
    public const int MaxAgents = 8;
    public const int MaxAgentNameCharacters = 120;
    public const int MaxPromptBytes = 16 * 1024;
    public const int MaxTotalPromptBytes = 48 * 1024;
    public const int MaxInstructionCharacters = 4_000;
    public const int MaxRequestBytes = 384 * 1024;
    public const int MaxSelectedAgents = 3;
    public const int MaxAnalysisCharacters = 6_000;
    public const int MaxSynthesisCharacters = 10_000;

    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly Regex AgentIdPattern = new("^[A-Za-z0-9._-]{1,64}$", RegexOptions.CultureInvariant);
    private static readonly JsonSerializerOptions StrictJson = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    public static AgentAnalysisRequest CreateRequest(
        string instruction,
        IReadOnlyList<AgentAnalysisAgent> agents,
        bool userConsent)
    {
        if (!userConsent)
            throw new InvalidOperationException("Analiz isteği için kullanıcı onayı gereklidir.");
        if (string.IsNullOrWhiteSpace(instruction) || instruction.Length > MaxInstructionCharacters
            || instruction.Contains('\0'))
            throw new ArgumentException($"Talimat boş olamaz ve {MaxInstructionCharacters} karakteri aşamaz.");
        try
        {
            _ = StrictUtf8.GetByteCount(instruction);
        }
        catch (EncoderFallbackException error)
        {
            throw new ArgumentException("Talimat geçerli UTF-8 metni olmalıdır.", error);
        }
        if (agents is null || agents.Count is < 1 or > MaxAgents)
            throw new ArgumentException($"1 ile {MaxAgents} arasında ajan seçin.");

        var ids = new HashSet<string>(StringComparer.Ordinal);
        var totalPromptBytes = 0;
        var safeAgents = new List<AgentAnalysisAgent>(agents.Count);
        foreach (var agent in agents)
        {
            if (agent is null
                || string.IsNullOrEmpty(agent.id)
                || !AgentIdPattern.IsMatch(agent.id)
                || !ids.Add(agent.id)
                || agent.name is null
                || string.IsNullOrWhiteSpace(agent.name)
                || agent.name.Length > MaxAgentNameCharacters
                || agent.name.Any(character => character < 32 || character == 127)
                || agent.prompt is null
                || string.IsNullOrWhiteSpace(agent.prompt)
                || agent.prompt.Contains('\0'))
                throw new ArgumentException("Ajan kimliği, adı veya tam metni geçersiz.");

            int promptBytes;
            try
            {
                promptBytes = StrictUtf8.GetByteCount(agent.prompt);
                _ = StrictUtf8.GetByteCount(agent.name);
            }
            catch (EncoderFallbackException error)
            {
                throw new ArgumentException("Ajan adı ve tam metni geçerli UTF-8 olmalıdır.", error);
            }
            if (promptBytes > MaxPromptBytes)
                throw new InvalidDataException($"Her ajan metni {MaxPromptBytes / 1024} KiB sınırını aşamaz.");
            totalPromptBytes += promptBytes;
            if (totalPromptBytes > MaxTotalPromptBytes)
                throw new InvalidDataException($"Ajan metinlerinin toplamı {MaxTotalPromptBytes / 1024} KiB sınırını aşamaz.");

            safeAgents.Add(new AgentAnalysisAgent
            {
                id = agent.id,
                name = agent.name.Trim(),
                prompt = agent.prompt
            });
        }

        var request = new AgentAnalysisRequest
        {
            instruction = instruction.Trim(),
            consent = true,
            agents = safeAgents
        };
        if (JsonSerializer.SerializeToUtf8Bytes(request, StrictJson).Length > MaxRequestBytes)
            throw new InvalidDataException($"Analiz isteği {MaxRequestBytes / 1024} KiB sınırını aşamaz.");
        return request;
    }

    public static AgentAnalysisResponse ValidateResponse(
        AgentAnalysisResponse response,
        IReadOnlyList<AgentAnalysisAgent> submittedAgents)
    {
        if (response.selected_agents is null || response.analyses is null || response.synthesis is null)
            throw new InvalidDataException("Sunucudan geçersiz analiz yanıtı alındı.");
        if (response.selected_agents.Any(agent => agent is null)
            || response.analyses.Any(result => result is null))
            throw new InvalidDataException("Sunucudan geçersiz analiz yanıtı alındı.");
        var knownAgents = submittedAgents.ToDictionary(agent => agent.id, StringComparer.Ordinal);
        if (response.selected_agents.Count > MaxSelectedAgents
            || response.selected_agents.Select(agent => agent.id).Distinct(StringComparer.Ordinal).Count()
                != response.selected_agents.Count
            || response.selected_agents.Any(agent =>
                !knownAgents.TryGetValue(agent.id, out var submitted)
                || !string.Equals(agent.name, submitted.name, StringComparison.Ordinal))
            || response.analyses.Count != response.selected_agents.Count
            || response.analyses.Select(result => result.agent_id).Distinct(StringComparer.Ordinal).Count()
                != response.analyses.Count
            || response.analyses.Any(result =>
                !response.selected_agents.Any(agent => agent.id == result.agent_id)
                || string.IsNullOrWhiteSpace(result.analysis)
                || result.analysis.Contains('\0')
                || result.analysis.Length > MaxAnalysisCharacters)
            || string.IsNullOrWhiteSpace(response.synthesis)
            || response.synthesis.Contains('\0')
            || response.synthesis.Length > MaxSynthesisCharacters)
            throw new InvalidDataException("Sunucudan geçersiz analiz yanıtı alındı.");
        return response;
    }
}

internal sealed class AgentAnalysisClient : IDisposable
{
    private static readonly HttpClient SharedHttp = new(new SocketsHttpHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromMinutes(2)
    };
    private static readonly JsonSerializerOptions StrictJson = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };
    private readonly HttpClient http;
    private readonly bool ownsHttp;
    private readonly string serverUrl;
    private readonly string apiKey;

    public AgentAnalysisClient(string serverUrl, string apiKey, HttpMessageHandler? handler = null)
    {
        this.serverUrl = TransferSecurity.ValidateServerUrl(serverUrl);
        if (string.IsNullOrWhiteSpace(apiKey) || apiKey.Contains('\r') || apiKey.Contains('\n'))
            throw new ArgumentException("Uzman analizi için ASISTAN_API_KEY girilmelidir.");
        this.apiKey = apiKey;
        http = handler is null ? SharedHttp : new HttpClient(handler);
        ownsHttp = handler is not null;
        if (ownsHttp)
            http.Timeout = TimeSpan.FromMinutes(2);
    }

    public async Task<AgentAnalysisResponse> AnalyzeAsync(
        string instruction,
        IReadOnlyList<AgentAnalysisAgent> agents,
        bool userConsent,
        CancellationToken cancellationToken)
    {
        var requestBody = AgentAnalysisSecurity.CreateRequest(instruction, agents, userConsent);
        var json = JsonSerializer.SerializeToUtf8Bytes(requestBody, StrictJson);
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{serverUrl}/workspace/agents/analyze");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
        request.Content = new ByteArrayContent(json);
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        using var response = await http.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException($"Uzman analizi isteği başarısız oldu ({(int)response.StatusCode}).",
                null, response.StatusCode);

        var responseBody = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (responseBody.Length > AgentAnalysisSecurity.MaxRequestBytes)
            throw new InvalidDataException("Sunucu yanıtı izin verilen boyutu aşıyor.");
        var parsed = JsonSerializer.Deserialize<AgentAnalysisResponse>(responseBody, StrictJson)
            ?? throw new InvalidDataException("Sunucudan boş analiz yanıtı alındı.");
        return AgentAnalysisSecurity.ValidateResponse(parsed, requestBody.agents);
    }

    public void Dispose()
    {
        if (ownsHttp)
            http.Dispose();
    }
}
