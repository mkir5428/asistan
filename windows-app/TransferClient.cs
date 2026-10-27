using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Cip.Windows;

internal sealed class TransferClient : IDisposable
{
    private static readonly HttpClient SharedHttp = new(new SocketsHttpHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromMinutes(12)
    };
    private readonly HttpClient http;
    private readonly string serverUrl;
    private readonly string pairingId;
    private readonly string pcToken;
    private readonly bool ownsHttp;

    public TransferClient(string serverUrl, string pairingId, string pcToken, HttpMessageHandler? handler = null)
    {
        this.serverUrl = TransferSecurity.ValidateServerUrl(serverUrl);
        this.pairingId = pairingId;
        this.pcToken = pcToken;
        http = handler is null ? SharedHttp : new HttpClient(handler);
        ownsHttp = handler is not null;
        if (ownsHttp)
            http.Timeout = TimeSpan.FromMinutes(12);
    }

    public async Task<PairingCreated> CreatePairingAsync(CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, Url("pairings"));
        using var response = await http.SendAsync(request, cancellationToken);
        return await ReadJsonAsync<PairingCreated>(response, cancellationToken);
    }

    public async Task<string> GetPairingStateAsync(string id, string token, CancellationToken cancellationToken)
    {
        using var request = Authenticated(HttpMethod.Get, $"pairings/{Escape(id)}", token);
        using var response = await http.SendAsync(request, cancellationToken);
        using var json = await ReadDocumentAsync(response, cancellationToken);
        return json.RootElement.GetProperty("state").GetString() ?? "";
    }

    public async Task RevokePairingAsync(CancellationToken cancellationToken)
    {
        using var request = Authenticated(HttpMethod.Delete, $"pairings/{Escape(pairingId)}", pcToken);
        using var response = await http.SendAsync(request, cancellationToken);
        await EnsureSuccessAsync(response, cancellationToken);
    }

    public async Task<TransferAction> CreateActionAsync(
        string direction, string filename, long size, string sha256, CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new
        {
            pairing_id = pairingId,
            direction,
            filename,
            size,
            sha256
        });
        using var request = Authenticated(HttpMethod.Post, "actions", pcToken);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, cancellationToken);
        var result = await ReadJsonAsync<TransferAction>(response, cancellationToken);
        return result;
    }

    public async Task<IReadOnlyList<TransferAction>> GetActionsAsync(CancellationToken cancellationToken)
    {
        using var request = Authenticated(HttpMethod.Get,
            $"actions?pairing_id={Uri.EscapeDataString(pairingId)}", pcToken);
        using var response = await http.SendAsync(request, cancellationToken);
        using var json = await ReadDocumentAsync(response, cancellationToken);
        var actions = json.RootElement.GetProperty("actions")
            .Deserialize<List<TransferAction>>() ?? [];
        return actions;
    }

    public async Task UploadApprovedFileAsync(TransferAction action, CancellationToken cancellationToken)
    {
        if (action.state != "approved" || action.direction != "pc_to_phone")
            throw new InvalidOperationException("Yükleme için Android onaylı bir bilgisayardan telefona işlemi gerekir.");
        if (string.IsNullOrEmpty(action.source_path))
            throw new InvalidOperationException("Yüklenecek yerel dosya yolu bulunamadı.");

        await using var stream = new FileStream(action.source_path, FileMode.Open, FileAccess.Read, FileShare.Read,
            64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (stream.Length != action.size || stream.Length > TransferSecurity.MaxFileBytes)
            throw new InvalidDataException("Dosya boyutu onaylanan metadata ile eşleşmiyor.");

        var actualHash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
        if (!CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(actualHash), Encoding.ASCII.GetBytes(action.sha256)))
            throw new InvalidDataException("Dosya içeriği onaylanan SHA-256 ile eşleşmiyor.");
        stream.Position = 0;

        using var request = Authenticated(HttpMethod.Put, $"actions/{Escape(action.action_id)}/data", pcToken);
        request.Headers.Add("X-Cip-Pairing-Id", pairingId);
        var content = new StreamContent(stream, 64 * 1024);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        content.Headers.ContentLength = action.size;
        request.Content = content;
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        await EnsureSuccessAsync(response, cancellationToken);
    }

    public async Task DownloadReadyFileAsync(
        TransferAction action, string destinationPath, CancellationToken cancellationToken)
    {
        if (action.state != "ready" || action.direction != "phone_to_pc")
            throw new InvalidOperationException("İndirme için Android tarafından gönderilmiş hazır bir işlem gerekir.");

        var temporaryPath = destinationPath + "." + Guid.NewGuid().ToString("N") + ".partial";
        try
        {
            using var request = Authenticated(HttpMethod.Get,
                $"actions/{Escape(action.action_id)}/data?pairing_id={Uri.EscapeDataString(pairingId)}", pcToken);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            await EnsureSuccessAsync(response, cancellationToken);
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            await using var output = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[64 * 1024];
            long total = 0;
            while (true)
            {
                var read = await input.ReadAsync(buffer, cancellationToken);
                if (read == 0)
                    break;
                total += read;
                if (total > action.size || total > TransferSecurity.MaxFileBytes)
                    throw new InvalidDataException("İndirilen dosya onaylanan boyutu aştı.");
                hash.AppendData(buffer, 0, read);
                await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            }

            await output.FlushAsync(cancellationToken);
            var actualHash = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
            if (total != action.size
                || !CryptographicOperations.FixedTimeEquals(
                    Encoding.ASCII.GetBytes(actualHash), Encoding.ASCII.GetBytes(action.sha256)))
                throw new InvalidDataException("İndirilen dosya boyutu veya SHA-256 değeri eşleşmiyor.");
            await output.DisposeAsync();
            File.Move(temporaryPath, destinationPath, overwrite: false);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public async Task CancelActionAsync(string actionId, CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new { pairing_id = pairingId });
        using var request = Authenticated(HttpMethod.Post, $"actions/{Escape(actionId)}/cancel", pcToken);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, cancellationToken);
        await EnsureSuccessAsync(response, cancellationToken);
    }

    private HttpRequestMessage Authenticated(HttpMethod method, string path, string token)
    {
        var request = new HttpRequestMessage(method, Url(path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return request;
    }

    private string Url(string path) => $"{serverUrl}/transfer/{path}";
    private static string Escape(string value) => Uri.EscapeDataString(value);

    private static async Task<T> ReadJsonAsync<T>(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        using var json = await ReadDocumentAsync(response, cancellationToken);
        return json.RootElement.Deserialize<T>()
            ?? throw new InvalidDataException("Sunucudan beklenmeyen boş JSON yanıtı alındı.");
    }

    private static async Task<JsonDocument> ReadDocumentAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        await EnsureSuccessAsync(response, cancellationToken);
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        return await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
    }

    private static async Task EnsureSuccessAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode)
            return;

        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        string code = "";
        try
        {
            using var json = JsonDocument.Parse(body);
            if (json.RootElement.TryGetProperty("error", out var error))
                code = error.GetString() ?? "";
        }
        catch (JsonException)
        {
            // Some reverse proxies return non-JSON errors; the HTTP status is still useful.
        }

        var status = (int)response.StatusCode;
        var detail = code.Length == 0 ? response.ReasonPhrase : code;
        throw new HttpRequestException($"Sunucu isteği reddetti ({status}): {detail}", null, response.StatusCode);
    }

    public void Dispose()
    {
        if (ownsHttp)
            http.Dispose();
    }
}
