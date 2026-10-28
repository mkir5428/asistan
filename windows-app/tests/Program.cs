using System.Net;
using System.Text;
using System.Text.Json;
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
    ("Hazır indirme boyutu ve özeti doğrular", CheckReadyDownload),
    ("Çalışma alanı yolları traversal ve Windows aygıt adlarını reddeder", CheckWorkspacePaths),
    ("Plan API yalnızca seçili dosyaları gönderir ve yanıtı doğrular", CheckWorkspacePlanRequest),
    ("Plan yanıtı tüm gönderilen dosyaları bir kez içermelidir", CheckWorkspacePlanValidation),
    ("Plan API talimat, dosya ve gerçek JSON istek sınırlarını uygular", CheckWorkspacePlanLimits),
    ("Ajan analizi isteği onaylı tam metinleri HTTPS API'sine eşler", CheckAgentAnalysisRequest),
    ("Ajan analizi açık onay olmadan ağa çıkmaz", CheckAgentAnalysisConsentGate),
    ("Ajan analizi talimat, ajan ve prompt boyutu sınırlarını uygular", CheckAgentAnalysisLimits),
    ("Plan değişiklikleri atomik yazılır ve eski dosya sürümü korunur", CheckWorkspaceAtomicWrite),
    ("Yerel sunucu profilleri çalışma alanı dışı çalışma klasörünü ve kabukları reddeder", CheckServerProfiles),
    ("Build/Test iş akışları kabuk başlatmaz ve çalışma alanı dışı klasörleri reddeder", CheckProjectWorkflows)
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

static void CheckWorkspacePaths()
{
    Equal("src/main.cs", WorkspaceSecurity.ValidateRelativePath("src/main.cs"));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidateRelativePath("../secret.txt"));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidateRelativePath("src\\main.cs"));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidateRelativePath("src//main.cs"));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidateRelativePath("CON.txt"));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidateRelativePath("C:/secret.txt"));
    Throws<EncoderFallbackException>(() => WorkspaceSecurity.ValidateRelativePath("bad-\uD800.txt"));
    Throws<EncoderFallbackException>(() => WorkspaceSecurity.ValidateInstruction("bad-\uD800"));

    var root = Path.Combine(Path.GetTempPath(), "cip-workspace-" + Guid.NewGuid().ToString("N"));
    var outside = Path.Combine(Path.GetTempPath(), "cip-outside-" + Guid.NewGuid().ToString("N") + ".txt");
    Directory.CreateDirectory(root);
    File.WriteAllText(outside, "outside");
    try
    {
        False(TransferSecurity.IsPathAllowed(outside, [root]));
    }
    finally
    {
        Directory.Delete(root, recursive: true);
        File.Delete(outside);
    }
}

static void CheckWorkspacePlanRequest()
{
    using var handler = new InspectHandler(async request =>
    {
        Equal(HttpMethod.Post, request.Method);
        Equal("https://example.test/workspace/plan", request.RequestUri!.ToString());
        Equal("Bearer", request.Headers.Authorization?.Scheme);
        Equal(new string("planning-secret".ToCharArray()), request.Headers.Authorization?.Parameter);
        Equal("application/json", request.Content!.Headers.ContentType?.MediaType);
        var body = await request.Content.ReadAsStringAsync();
        using var json = System.Text.Json.JsonDocument.Parse(body);
        var root = json.RootElement;
        Equal(2, root.EnumerateObject().Count());
        Equal("Refactor this file", root.GetProperty("instruction").GetString());
        var files = root.GetProperty("files");
        Equal(1, files.GetArrayLength());
        Equal(2, files[0].EnumerateObject().Count());
        Equal("src/main.cs", files[0].GetProperty("path").GetString());
        Equal("class Main {}", files[0].GetProperty("content").GetString());
        return JsonResponse(
            "{\"summary\":\"A concise plan\",\"changes\":[{\"relative_path\":\"src/main.cs\",\"replacement_content\":\"class Main { }\"}]}",
            HttpStatusCode.OK);
    });
    using var client = new WorkspacePlanClient("https://example.test", "planning-secret", handler);
    var response = client.CreatePlanAsync(
        "Refactor this file",
        [new WorkspacePlanFile { path = "src/main.cs", content = "class Main {}" }],
        CancellationToken.None).GetAwaiter().GetResult();
    Equal("A concise plan", response.summary);
    Equal("src/main.cs", response.changes.Single().relative_path);
    Equal("class Main { }", response.changes.Single().replacement_content);
}

static void CheckWorkspacePlanValidation()
{
    var submitted = new[]
    {
        new WorkspacePlanFile { path = "src/a.cs", content = "a" },
        new WorkspacePlanFile { path = "src/b.cs", content = "b" }
    };
    var exact = new WorkspacePlanResponse
    {
        summary = "Two changes",
        changes =
        [
            new WorkspacePlanChange { relative_path = "src/a.cs", replacement_content = "a2" },
            new WorkspacePlanChange { relative_path = "src/b.cs", replacement_content = "b2" }
        ]
    };
    Equal(2, WorkspaceSecurity.ValidatePlanResponse(exact, submitted).changes.Count);
    exact.changes.RemoveAt(1);
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidatePlanResponse(exact, submitted));
    exact.changes.Add(new WorkspacePlanChange { relative_path = "../outside", replacement_content = "x" });
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidatePlanResponse(exact, submitted));
}

static void CheckWorkspacePlanLimits()
{
    Throws<ArgumentException>(() => WorkspaceSecurity.ValidateInstruction(new string('x', 4001)));
    Throws<InvalidDataException>(() => WorkspaceSecurity.ValidatePlanRequest(new WorkspacePlanRequest
    {
        instruction = "change",
        files = [new WorkspacePlanFile { path = "large.txt", content = new string('x', 64 * 1024 + 1) }]
    }));

    var fileA = new WorkspacePlanFile { path = "a.txt", content = new string('"', 64 * 1024) };
    var fileB = new WorkspacePlanFile { path = "b.txt", content = new string('"', 64 * 1024) };
    var fileC = new WorkspacePlanFile { path = "c.txt", content = new string('"', 64 * 1024) };
    var fileD = new WorkspacePlanFile { path = "d.txt", content = new string('"', 64 * 1024) };
    var files = new[] { fileA, fileB, fileC, fileD };
    using var handler = new InspectHandler((Func<HttpRequestMessage, HttpResponseMessage>)(_ =>
        throw new InvalidOperationException("Sınır aşan plan isteği ağa gönderilmemeliydi.")));
    using var client = new WorkspacePlanClient("https://example.test", "planning-secret", handler);
    Throws<InvalidDataException>(() =>
        client.CreatePlanAsync("change", files, CancellationToken.None).GetAwaiter().GetResult());
}

static void CheckAgentAnalysisRequest()
{
    var agents = new[]
    {
        new AgentAnalysisAgent { id = "agent-1", name = "Security", prompt = "Review security." },
        new AgentAnalysisAgent { id = "agent-2", name = "Architecture", prompt = "Review boundaries." }
    };
    using var handler = new InspectHandler(async request =>
    {
        Equal(HttpMethod.Post, request.Method);
        Equal("https://example.test/workspace/agents/analyze", request.RequestUri!.ToString());
        Equal("Bearer planning-secret", request.Headers.Authorization?.ToString());
        Equal("application/json", request.Content!.Headers.ContentType?.MediaType);
        var body = await request.Content.ReadAsStringAsync();
        using var json = JsonDocument.Parse(body);
        var root = json.RootElement;
        Equal(3, root.EnumerateObject().Count());
        Equal("Review this design.", root.GetProperty("instruction").GetString());
        True(root.GetProperty("consent").GetBoolean());
        var submitted = root.GetProperty("agents");
        Equal(2, submitted.GetArrayLength());
        Equal("agent-1", submitted[0].GetProperty("id").GetString());
        Equal("Security", submitted[0].GetProperty("name").GetString());
        Equal("Review security.", submitted[0].GetProperty("prompt").GetString());
        Equal("agent-2", submitted[1].GetProperty("id").GetString());
        Equal("Review boundaries.", submitted[1].GetProperty("prompt").GetString());
        return JsonResponse(
            "{\"selected_agents\":[{\"id\":\"agent-2\",\"name\":\"Architecture\"}],"
            + "\"analyses\":[{\"agent_id\":\"agent-2\",\"analysis\":\"Keep boundaries explicit.\"}],"
            + "\"synthesis\":\"Separate responsibilities and test the boundary.\"}",
            HttpStatusCode.OK);
    });
    using var client = new AgentAnalysisClient("https://example.test", "planning-secret", handler);
    var response = client.AnalyzeAsync(
        "  Review this design.  ",
        agents,
        userConsent: true,
        CancellationToken.None).GetAwaiter().GetResult();
    Equal("agent-2", response.selected_agents.Single().id);
    Equal("Keep boundaries explicit.", response.analyses.Single().analysis);
    Equal("Separate responsibilities and test the boundary.", response.synthesis);
}

static void CheckAgentAnalysisConsentGate()
{
    using var handler = new InspectHandler((Func<HttpRequestMessage, HttpResponseMessage>)(_ =>
        throw new InvalidOperationException("Onaysız analiz HTTP isteği göndermemeliydi.")));
    using var client = new AgentAnalysisClient("https://example.test", "planning-secret", handler);
    Throws<InvalidOperationException>(() => client.AnalyzeAsync(
        "Review this design.",
        [new AgentAnalysisAgent { id = "agent-1", name = "Reviewer", prompt = "Review it." }],
        userConsent: false,
        CancellationToken.None).GetAwaiter().GetResult());
}

static void CheckAgentAnalysisLimits()
{
    var oneAgent = new[] { new AgentAnalysisAgent { id = "agent-1", name = "Reviewer", prompt = "Review it." } };
    var maxCandidates = Enumerable.Range(1, AgentAnalysisSecurity.MaxAgents)
        .Select(index => new AgentAnalysisAgent
        {
            id = $"agent-{index}",
            name = "Reviewer",
            prompt = "Review it."
        })
        .ToArray();
    Equal(AgentAnalysisSecurity.MaxAgents,
        AgentAnalysisSecurity.CreateRequest("Review", maxCandidates, true).agents.Count);
    var maxPromptAgents = Enumerable.Range(1, 3)
        .Select(index => new AgentAnalysisAgent
        {
            id = $"agent-{index}",
            name = "Reviewer",
            prompt = new string('x', AgentAnalysisSecurity.MaxPromptBytes)
        })
        .ToArray();
    var maxPromptsRequest = AgentAnalysisSecurity.CreateRequest("Review", maxPromptAgents, true);
    Equal(AgentAnalysisSecurity.MaxTotalPromptBytes,
        maxPromptsRequest.agents.Sum(agent => Encoding.UTF8.GetByteCount(agent.prompt)));
    True(JsonSerializer.SerializeToUtf8Bytes(maxPromptsRequest).Length <= AgentAnalysisSecurity.MaxRequestBytes);

    Throws<ArgumentException>(() => AgentAnalysisSecurity.CreateRequest(
        new string('x', AgentAnalysisSecurity.MaxInstructionCharacters + 1), oneAgent, true));
    Throws<ArgumentException>(() => AgentAnalysisSecurity.CreateRequest(
        "Review", [], true));
    Throws<ArgumentException>(() => AgentAnalysisSecurity.CreateRequest(
        "Review",
        Enumerable.Range(1, AgentAnalysisSecurity.MaxAgents + 1)
            .Select(index => new AgentAnalysisAgent
            {
                id = $"agent-{index}",
                name = "Reviewer",
                prompt = "Review it."
            })
            .ToArray(),
        true));
    Throws<InvalidDataException>(() => AgentAnalysisSecurity.CreateRequest(
        "Review",
        [new AgentAnalysisAgent
        {
            id = "agent-1",
            name = "Reviewer",
            prompt = new string('x', AgentAnalysisSecurity.MaxPromptBytes + 1)
        }],
        true));
    Throws<InvalidDataException>(() => AgentAnalysisSecurity.CreateRequest(
        "Review",
        Enumerable.Range(1, 5)
            .Select(index => new AgentAnalysisAgent
            {
                id = $"agent-{index}",
                name = "Reviewer",
                prompt = new string('x', 10_000)
            })
            .ToArray(),
        true));
    Throws<InvalidOperationException>(() => AgentAnalysisSecurity.CreateRequest("Review", oneAgent, false));
}

static void CheckWorkspaceAtomicWrite()
{
    var root = Path.Combine(Path.GetTempPath(), "cip-workspace-write-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(root);
    var relativePath = "settings.txt";
    var fullPath = Path.Combine(root, relativePath);
    File.WriteAllText(fullPath, "before", new UTF8Encoding(false));
    try
    {
        WorkspaceSecurity.WriteReplacementAtomically(root, relativePath, "before", "after");
        Equal("after", File.ReadAllText(fullPath));
        Throws<IOException>(() =>
            WorkspaceSecurity.WriteReplacementAtomically(root, relativePath, "before", "stale overwrite"));
        Equal("after", File.ReadAllText(fullPath));
    }
    finally
    {
        Directory.Delete(root, recursive: true);
    }
}

static void CheckServerProfiles()
{
    var root = Path.Combine(Path.GetTempPath(), "cip-server-profile-" + Guid.NewGuid().ToString("N"));
    var outside = Path.Combine(Path.GetTempPath(), "cip-server-outside-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(root);
    Directory.CreateDirectory(outside);
    var executable = Path.Combine(root, "server.exe");
    File.WriteAllText(executable, "test");
    var profile = new ServerProfile
    {
        Name = "test server",
        WorkspaceRoot = root,
        ExecutablePath = executable,
        WorkingDirectory = root,
        Arguments = "--port 8080"
    };
    try
    {
        WorkspaceSecurity.ValidateServerProfile(profile, root);
        profile.WorkingDirectory = outside;
        Throws<UnauthorizedAccessException>(() => WorkspaceSecurity.ValidateServerProfile(profile, root));
        profile.WorkingDirectory = root;
        profile.ExecutablePath = Path.Combine(root, "powershell.exe");
        File.WriteAllText(profile.ExecutablePath, "test");
        Throws<InvalidOperationException>(() => WorkspaceSecurity.ValidateServerProfile(profile, root));
    }
    finally
    {
        Directory.Delete(root, recursive: true);
        Directory.Delete(outside, recursive: true);
    }
}

static void CheckProjectWorkflows()
{
    var root = Path.Combine(Path.GetTempPath(), "cip-workflow-" + Guid.NewGuid().ToString("N"));
    var outside = Path.Combine(Path.GetTempPath(), "cip-workflow-outside-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(root);
    Directory.CreateDirectory(outside);
    var executable = Path.Combine(root, "builder.exe");
    File.WriteAllText(executable, "test");
    var workflow = new ProjectWorkflow
    {
        Name = "Project tests",
        Kind = "test",
        WorkspaceRoot = root,
        ExecutablePath = executable,
        WorkingDirectory = root,
        Arguments = "--test"
    };
    try
    {
        WorkspaceSecurity.ValidateProjectWorkflow(workflow, root);
        var startInfo = WorkspaceSecurity.CreateConfiguredProcessStartInfo(
            executable, root, "--message \"literal & not a shell\"");
        False(startInfo.UseShellExecute);
        True(startInfo.RedirectStandardOutput && startInfo.RedirectStandardError);
        Equal(executable, startInfo.FileName);
        Equal(root, startInfo.WorkingDirectory);
        Equal("--message \"literal & not a shell\"", startInfo.Arguments);

        workflow.WorkingDirectory = outside;
        Throws<UnauthorizedAccessException>(() => WorkspaceSecurity.ValidateProjectWorkflow(workflow, root));
        workflow.WorkingDirectory = root;
        workflow.Kind = "custom";
        Throws<InvalidOperationException>(() => WorkspaceSecurity.ValidateProjectWorkflow(workflow, root));
        workflow.Kind = "build";
        workflow.ExecutablePath = Path.Combine(root, "cmd.exe");
        File.WriteAllText(workflow.ExecutablePath, "test");
        Throws<InvalidOperationException>(() => WorkspaceSecurity.ValidateProjectWorkflow(workflow, root));
        workflow.ExecutablePath = executable;
        workflow.Arguments = "--run" + Environment.NewLine + "second";
        Throws<ArgumentException>(() => WorkspaceSecurity.ValidateProjectWorkflow(workflow, root));
    }
    finally
    {
        Directory.Delete(root, recursive: true);
        Directory.Delete(outside, recursive: true);
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
