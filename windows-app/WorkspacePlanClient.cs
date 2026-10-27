using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Cip.Windows;

internal sealed class WorkspacePlanClient : IDisposable
{
    private static readonly JsonSerializerOptions StrictJson = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };
    private static readonly HttpClient SharedHttp = new(new SocketsHttpHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromMinutes(2)
    };
    private readonly HttpClient http;
    private readonly bool ownsHttp;
    private readonly string serverUrl;

    public WorkspacePlanClient(string serverUrl, string apiKey, HttpMessageHandler? handler = null)
    {
        this.serverUrl = TransferSecurity.ValidateServerUrl(serverUrl);
        if (string.IsNullOrWhiteSpace(apiKey) || apiKey.Contains('\r') || apiKey.Contains('\n'))
            throw new ArgumentException("Çalışma alanı planlaması için ASISTAN_API_KEY girilmelidir.");
        http = handler is null ? SharedHttp : new HttpClient(handler);
        ownsHttp = handler is not null;
        if (ownsHttp)
            http.Timeout = TimeSpan.FromMinutes(2);
        this.apiKey = apiKey;
    }

    private readonly string apiKey;

    public async Task<WorkspacePlanResponse> CreatePlanAsync(
        string instruction, IReadOnlyList<WorkspacePlanFile> files, CancellationToken cancellationToken)
    {
        var requestBody = WorkspaceSecurity.ValidatePlanRequest(new WorkspacePlanRequest
        {
            instruction = instruction,
            files = files.ToList()
        });

        var json = JsonSerializer.SerializeToUtf8Bytes(requestBody, StrictJson);
        if (json.Length > WorkspaceSecurity.MaxRequestBytes)
            throw new InvalidDataException("Plan isteği 512 KiB sınırını aşıyor.");

        using var request = new HttpRequestMessage(HttpMethod.Post, $"{serverUrl}/workspace/plan");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
        request.Content = new ByteArrayContent(json);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        using var response = await http.SendAsync(request, cancellationToken);
        var responseBody = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var detail = response.ReasonPhrase;
            try
            {
                using var error = JsonDocument.Parse(responseBody);
                if (error.RootElement.TryGetProperty("error", out var value))
                    detail = value.GetString() ?? detail;
            }
            catch (JsonException)
            {
                // Non-JSON proxy errors still provide a useful status.
            }
            throw new HttpRequestException($"Plan isteği reddedildi ({(int)response.StatusCode}): {detail}",
                null, response.StatusCode);
        }
        if (responseBody.Length > WorkspaceSecurity.MaxRequestBytes)
            throw new InvalidDataException("Plan yanıtı 512 KiB sınırını aşıyor.");

        var plan = JsonSerializer.Deserialize<WorkspacePlanResponse>(responseBody, StrictJson)
            ?? throw new InvalidDataException("Sunucudan boş plan yanıtı alındı.");
        return WorkspaceSecurity.ValidatePlanResponse(plan, requestBody.files);
    }

    public void Dispose()
    {
        if (ownsHttp)
            http.Dispose();
    }
}
