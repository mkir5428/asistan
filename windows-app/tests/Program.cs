using System.Net;
using System.Text;
using Cip.Windows;

var checks = new List<(string Name, Action Run)>
{
    ("Yalnız HTTPS adresleri kabul edilir", CheckHttps),
    ("Klasör izin denetimi üst dizin ve kardeş klasörleri reddeder", CheckAllowedFolders),
    ("Windows için güvenli olmayan uzak dosya adları reddedilir", CheckRemoteNames),
    ("Eşleştirme oluşturma isteği API anahtarı veya bearer göndermez", CheckPairingRequest),
    ("Eylem istekleri yalnız PC token kullanır ve doğru metadata gönderir", CheckActionRequest),
    ("Eylem kuyruğu PC token ile durumları getirir", CheckActionList),
    ("Android onayı olmadan dosya yüklemesi yapılamaz", CheckUploadApprovalGate),
    ("Onaylı yükleme doğru ham gövde ve başlıkları kullanır", CheckApprovedUpload),
    ("Hazır indirme boyutu ve özeti doğrular", CheckReadyDownload)
};

foreach (var check in checks)
{
    check.Run();
    Console.WriteLine($"PASS {check.Name}");
}

Console.WriteLine($"{checks.Count} kontrol başarılı.");

static void CheckHttps()
{
    Equal("https://example.test", TransferSecurity.ValidateServerUrl(" https://example.test/ "));
    Throws<ArgumentException>(() => TransferSecurity.ValidateServerUrl("http://example.test"));
    Throws<ArgumentException>(() => TransferSecurity.ValidateServerUrl("https://user:pass@example.test"));
}

static void CheckAllowedFolders()
{
    var root = Path.Combine(Path.GetTempPath(), "cip-allowlist-" + Guid.NewGuid().ToString("N"));
    var selected = Path.Combine(root, "shared");
    var sibling = Path.Combine(root, "shared-evil");
    Directory.CreateDirectory(selected);
    Directory.CreateDirectory(sibling);
    try
    {
        True(TransferSecurity.IsPathAllowed(Path.Combine(selected, "report.txt"), [selected]));
        False(TransferSecurity.IsPathAllowed(Path.Combine(sibling, "report.txt"), [selected]));
        False(TransferSecurity.IsPathAllowed(Path.Combine(selected, "..", "outside.txt"), [selected]));
        True(TransferSecurity.IsPathAllowed(selected, [selected]));
    }
    finally
    {
        Directory.Delete(root, recursive: true);
    }
}

static void CheckRemoteNames()
{
    Equal("rapor.pdf", TransferSecurity.ValidateRemoteFilename("rapor.pdf"));
    Throws<InvalidDataException>(() => TransferSecurity.ValidateRemoteFilename("../rapor.pdf"));
    Throws<InvalidDataException>(() => TransferSecurity.ValidateRemoteFilename("CON.txt"));
    Throws<InvalidDataException>(() => TransferSecurity.ValidateRemoteFilename("rapor."));
}

static void CheckPairingRequest()
{
    using var handler = new InspectHandler(request =>
    {
        Equal(HttpMethod.Post, request.Method);
        Equal("https://example.test/transfer/pairings", request.RequestUri!.ToString());
        True(request.Headers.Authorization is null);
        return JsonResponse("{\"pairing_id\":\"p1\",\"pairing_code\":\"0123456789\",\"pc_token\":\"pc-secret\",\"pairing_code_expires_in\":300,\"pairing_expires_in\":2592000}", HttpStatusCode.Created);
    });
    using var client = new TransferClient("https://example.test", "", "", handler);
    var response = client.CreatePairingAsync(CancellationToken.None).GetAwaiter().GetResult();
    Equal("pc-secret", response.pc_token);
}

static void CheckActionRequest()
{
    using var handler = new InspectHandler(async request =>
    {
        Equal(HttpMethod.Post, request.Method);
        Equal("Bearer pc-secret", request.Headers.Authorization?.ToString());
        True(request.Headers.Authorization?.Parameter != "ASISTAN_API_KEY");
        Equal("https://example.test/transfer/actions", request.RequestUri!.ToString());
        var body = await request.Content!.ReadAsStringAsync();
        using var json = System.Text.Json.JsonDocument.Parse(body);
        var root = json.RootElement;
        Equal(5, root.EnumerateObject().Count());
        Equal("pair-1", root.GetProperty("pairing_id").GetString());
        Equal("pc_to_phone", root.GetProperty("direction").GetString());
        Equal("report.pdf", root.GetProperty("filename").GetString());
        Equal(12L, root.GetProperty("size").GetInt64());
        Equal(new string('a', 64), root.GetProperty("sha256").GetString());
        return JsonResponse("{\"action_id\":\"a1\",\"direction\":\"pc_to_phone\",\"filename\":\"report.pdf\",\"size\":12,\"sha256\":\"" + new string('a', 64) + "\",\"state\":\"awaiting_approval\",\"expires_at\":1900000000}", HttpStatusCode.Created);
    });
    using var client = new TransferClient("https://example.test", "pair-1", "pc-secret", handler);
    var action = client.CreateActionAsync(
        "pc_to_phone", "report.pdf", 12, new string('a', 64), CancellationToken.None).GetAwaiter().GetResult();
    Equal("a1", action.action_id);
    Equal("awaiting_approval", action.state);
}

static void CheckActionList()
{
    using var handler = new InspectHandler(request =>
    {
        Equal(HttpMethod.Get, request.Method);
        Equal("Bearer pc-secret", request.Headers.Authorization?.ToString());
        Equal("https://example.test/transfer/actions?pairing_id=pair-1", request.RequestUri!.ToString());
        return JsonResponse("{\"actions\":[{\"action_id\":\"a2\",\"direction\":\"phone_to_pc\",\"filename\":\"phone.txt\",\"size\":8,\"sha256\":\"" + new string('b', 64) + "\",\"state\":\"ready\",\"expires_at\":1900000000}]}", HttpStatusCode.OK);
    });
    using var client = new TransferClient("https://example.test", "pair-1", "pc-secret", handler);
    var actions = client.GetActionsAsync(CancellationToken.None).GetAwaiter().GetResult();
    Equal(1, actions.Count);
    Equal("phone_to_pc", actions[0].direction);
    Equal("ready", actions[0].state);
}

static void CheckUploadApprovalGate()
{
    using var handler = new InspectHandler((Func<HttpRequestMessage, HttpResponseMessage>)(_ =>
        throw new InvalidOperationException("Onaysız eylem HTTP isteği göndermemeliydi.")));
    using var client = new TransferClient("https://example.test", "pair-1", "pc-secret", handler);
    Throws<InvalidOperationException>(() =>
        client.UploadApprovedFileAsync(
            new TransferAction { state = "awaiting_approval", direction = "pc_to_phone" },
            CancellationToken.None).GetAwaiter().GetResult());
}

static void CheckApprovedUpload()
{
    var directory = Path.Combine(Path.GetTempPath(), "cip-upload-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(directory);
    var path = Path.Combine(directory, "payload.bin");
    var payload = Encoding.UTF8.GetBytes("onaylanmış içerik");
    File.WriteAllBytes(path, payload);
    try
    {
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(payload)).ToLowerInvariant();
        using var handler = new InspectHandler(async request =>
        {
            Equal(HttpMethod.Put, request.Method);
            Equal("Bearer pc-secret", request.Headers.Authorization?.ToString());
            Equal("pair-1", request.Headers.GetValues("X-Cip-Pairing-Id").Single());
            Equal("application/octet-stream", request.Content!.Headers.ContentType?.MediaType);
            Equal((long)payload.Length, request.Content.Headers.ContentLength);
            True((await request.Content.ReadAsByteArrayAsync()).SequenceEqual(payload));
            return JsonResponse("{\"action_id\":\"a1\",\"state\":\"ready\"}", HttpStatusCode.Created);
        });
        using var client = new TransferClient("https://example.test", "pair-1", "pc-secret", handler);
        client.UploadApprovedFileAsync(new TransferAction
        {
            action_id = "a1",
            direction = "pc_to_phone",
            filename = "payload.bin",
            size = payload.Length,
            sha256 = hash,
            state = "approved",
            source_path = path
        }, CancellationToken.None).GetAwaiter().GetResult();
    }
    finally
    {
        Directory.Delete(directory, recursive: true);
    }
}

static void CheckReadyDownload()
{
    var directory = Path.Combine(Path.GetTempPath(), "cip-download-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(directory);
    var destination = Path.Combine(directory, "phone.txt");
    var payload = Encoding.UTF8.GetBytes("phone payload");
    var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(payload)).ToLowerInvariant();
    try
    {
        using var handler = new InspectHandler(request =>
        {
            Equal(HttpMethod.Get, request.Method);
            Equal("Bearer pc-secret", request.Headers.Authorization?.ToString());
            Equal("https://example.test/transfer/actions/a2/data?pairing_id=pair-1", request.RequestUri!.ToString());
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(payload) };
        });
        using var client = new TransferClient("https://example.test", "pair-1", "pc-secret", handler);
        client.DownloadReadyFileAsync(new TransferAction
        {
            action_id = "a2",
            direction = "phone_to_pc",
            filename = "phone.txt",
            size = payload.Length,
            sha256 = hash,
            state = "ready"
        }, destination, CancellationToken.None).GetAwaiter().GetResult();
        True(File.ReadAllBytes(destination).SequenceEqual(payload));
    }
    finally
    {
        Directory.Delete(directory, recursive: true);
    }
}

static HttpResponseMessage JsonResponse(string json, HttpStatusCode status) =>
    new(status) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

static void True(bool value)
{
    if (!value) throw new InvalidOperationException("Beklenen değer true değil.");
}

static void False(bool value) => True(!value);

static void Equal<T>(T expected, T actual)
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
        throw new InvalidOperationException($"Beklenen: {expected}; gelen: {actual}");
}

static void Throws<T>(Action action) where T : Exception
{
    try { action(); }
    catch (T) { return; }
    throw new InvalidOperationException($"{typeof(T).Name} bekleniyordu.");
}

sealed class InspectHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
{
    public InspectHandler(Func<HttpRequestMessage, HttpResponseMessage> send)
        : this(request => Task.FromResult(send(request))) { }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
        send(request);
}
