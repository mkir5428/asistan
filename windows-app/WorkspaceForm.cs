using System.Diagnostics;
using System.Text;

namespace Cip.Windows;

internal sealed class WorkspaceForm : Form
{
    private readonly AppState state;
    private readonly Action saveState;
    private readonly CancellationTokenSource lifetime = new();
    private readonly TabControl tabs = new() { Dock = DockStyle.Fill };
    private readonly ListBox workspaceFiles = new() { Dock = DockStyle.Fill, IntegralHeight = false };
    private readonly TextBox workspacePath = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox planningUrl = new() { Dock = DockStyle.Fill };
    private readonly TextBox apiKey = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly TextBox instruction = new() { Dock = DockStyle.Fill, Multiline = true, ScrollBars = ScrollBars.Vertical };
    private readonly Button createPlanButton = new() { Text = "Plan oluştur ve farkları incele", AutoSize = true, Height = 36 };
    private readonly List<(string Path, AgentAnalysisAgent Agent)> analysisDocuments = [];
    private readonly ListBox analysisAgentList = new() { Dock = DockStyle.Fill, IntegralHeight = false };
    private readonly RichTextBox analysisPromptPreview = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        DetectUrls = false,
        WordWrap = true,
        BackColor = SystemColors.Window
    };
    private readonly TextBox analysisInstruction = new()
    {
        Dock = DockStyle.Fill,
        Multiline = true,
        ScrollBars = ScrollBars.Vertical
    };
    private readonly CheckBox analysisConsent = new()
    {
        AutoSize = false,
        Dock = DockStyle.Fill,
        TextAlign = ContentAlignment.MiddleLeft,
        Text = "Onaylıyorum: talimat ve seçili tüm .md dosyalarının tam metni HTTPS üzerinden sunucuya gönderilip Claude tarafından analiz edilecek."
    };
    private readonly Button analyzeAgentsButton = new()
    {
        Text = "Uzman analizini başlat",
        AutoSize = true,
        Height = 36,
        Enabled = false
    };
    private readonly Label analysisStatus = new()
    {
        AutoSize = true,
        MaximumSize = new Size(520, 0),
        Text = "Ajan seçin ve Claude'a gönderilecek tam metinleri inceleyin."
    };
    private readonly RichTextBox analysisResult = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        DetectUrls = false,
        WordWrap = true,
        BackColor = SystemColors.Window
    };
    private readonly ListBox profiles = new() { Dock = DockStyle.Fill, DisplayMember = nameof(ServerProfile.Name) };
    private readonly TextBox profileName = new() { Dock = DockStyle.Fill };
    private readonly TextBox executablePath = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox workingDirectory = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox arguments = new() { Dock = DockStyle.Fill };
    private readonly Label serverStatus = new() { Dock = DockStyle.Fill, AutoEllipsis = true, Text = "Başlatılmış profil yok." };
    private readonly ListBox workflows = new() { Dock = DockStyle.Fill, DisplayMember = nameof(ProjectWorkflow.Name) };
    private readonly ComboBox workflowKind = new() { Dock = DockStyle.Fill, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox workflowName = new() { Dock = DockStyle.Fill };
    private readonly TextBox workflowExecutable = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox workflowWorkingDirectory = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox workflowArguments = new() { Dock = DockStyle.Fill };
    private readonly Label workflowStatus = new() { Dock = DockStyle.Fill, AutoEllipsis = true, Text = "Çalışan iş akışı yok." };
    private readonly RichTextBox workflowLog = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        DetectUrls = false,
        WordWrap = false,
        BackColor = SystemColors.Window
    };
    private readonly RichTextBox serverLog = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        DetectUrls = false,
        WordWrap = false,
        BackColor = SystemColors.Window
    };
    private Process? managedProcess;
    private Label? managedProcessStatus;
    private RichTextBox? managedProcessLog;
    private string managedProcessDescription = "";
    private string managedProcessKind = "";
    private bool closing;

    public WorkspaceForm(AppState state, Action saveState)
    {
        this.state = state;
        this.saveState = saveState;
        Text = "Çip - Çalışma alanı ve yerel sunucu";
        Width = 940;
        Height = 760;
        MinimumSize = new Size(780, 620);
        StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Segoe UI", 9F);
        planningUrl.Text = state.PlanningServerUrl.Length > 0 ? state.PlanningServerUrl : state.ServerUrl;
        apiKey.Text = state.PlanningApiKey;
        instruction.MaxLength = WorkspaceSecurity.MaxInstructionCharacters;
        createPlanButton.Click += async (_, _) => await CreatePlanAsync();
        FormClosing += (_, args) =>
        {
            closing = true;
            lifetime.Cancel();
            try
            {
                StopManagedProcess();
            }
            catch (Exception error)
            {
                closing = false;
                args.Cancel = true;
                MessageBox.Show(this, error.Message, "Yerel sunucu durdurulamadı", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        };
        FormClosed += (_, _) => lifetime.Dispose();

        BuildWorkspaceTab();
        BuildAgentAnalysisTab();
        BuildServerTab();
        BuildWorkflowTab();
        Controls.Add(tabs);
        LoadWorkspaceState();
        RefreshProfiles();
        RefreshWorkflows();
    }

    private void BuildWorkspaceTab()
    {
        var page = new TabPage("Çalışma alanı");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 1,
            RowCount = 3
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 72));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 58));
        page.Controls.Add(layout);

        var rootGroup = new GroupBox { Text = "Seçilmiş çalışma alanı", Dock = DockStyle.Fill, Padding = new Padding(8) };
        var rootLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        rootLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        rootLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140));
        rootLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 95));
        rootGroup.Controls.Add(rootLayout);
        rootLayout.Controls.Add(workspacePath, 0, 0);
        var chooseRoot = new Button { Text = "Klasör seç", Dock = DockStyle.Fill };
        chooseRoot.Click += (_, _) => ChooseWorkspaceRoot();
        rootLayout.Controls.Add(chooseRoot, 1, 0);
        var clearRoot = new Button { Text = "Temizle", Dock = DockStyle.Fill };
        clearRoot.Click += (_, _) => ClearWorkspaceRoot();
        rootLayout.Controls.Add(clearRoot, 2, 0);
        layout.Controls.Add(rootGroup, 0, 0);

        var filesGroup = new GroupBox { Text = "Plan için açıkça seçilen dosyalar (en fazla 8)", Dock = DockStyle.Fill, Padding = new Padding(8) };
        filesGroup.Controls.Add(workspaceFiles);
        var fileButtons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 38 };
        filesGroup.Controls.Add(fileButtons);
        var addFiles = new Button { Text = "Dosya ekle" };
        addFiles.Click += (_, _) => AddWorkspaceFiles();
        fileButtons.Controls.Add(addFiles);
        var removeFile = new Button { Text = "Seçileni kaldır" };
        removeFile.Click += (_, _) => RemoveWorkspaceFile();
        fileButtons.Controls.Add(removeFile);
        layout.Controls.Add(filesGroup, 0, 1);

        var planGroup = new GroupBox { Text = "Çalışma alanı planı", Dock = DockStyle.Fill, Padding = new Padding(8) };
        var planLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 4 };
        planLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 145));
        planLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        planLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        planLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        planLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        planLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        planGroup.Controls.Add(planLayout);
        planLayout.Controls.Add(new Label { Text = "HTTPS sunucu", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        planLayout.Controls.Add(planningUrl, 1, 0);
        planLayout.Controls.Add(new Label { Text = "ASISTAN_API_KEY", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        planLayout.Controls.Add(apiKey, 1, 1);
        planLayout.Controls.Add(new Label { Text = "Talimat", AutoSize = true, Anchor = AnchorStyles.Left | AnchorStyles.Top }, 0, 2);
        planLayout.Controls.Add(instruction, 1, 2);
        var planActions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        planLayout.Controls.Add(planActions, 0, 3);
        planLayout.SetColumnSpan(planActions, 2);
        planActions.Controls.Add(createPlanButton);
        planActions.Controls.Add(new Label
        {
            Text = "Yalnızca seçili dosyalar gönderilir; anahtar sadece /workspace/plan için kullanılır.",
            AutoSize = true,
            MaximumSize = new Size(490, 0),
            Padding = new Padding(4, 10, 0, 0)
        });
        layout.Controls.Add(planGroup, 0, 2);
        tabs.TabPages.Add(page);
    }

    private void BuildAgentAnalysisTab()
    {
        var page = new TabPage("Uzman analizi");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 1,
            RowCount = 4
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 48));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 20));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 74));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 32));
        page.Controls.Add(layout);

        var agentsGroup = new GroupBox
        {
            Text = "Claude'un seçebileceği yerel .md ajanları (en fazla 8)",
            Dock = DockStyle.Fill,
            Padding = new Padding(8)
        };
        var agentsLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2
        };
        agentsLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        agentsLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        agentsLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        agentsLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        agentsGroup.Controls.Add(agentsLayout);

        var agentButtons = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
        var addAgents = new Button { Text = ".md ajanı seç", AutoSize = true };
        addAgents.Click += (_, _) => AddAnalysisAgents();
        agentButtons.Controls.Add(addAgents);
        var removeAgent = new Button { Text = "Seçileni kaldır", AutoSize = true };
        removeAgent.Click += (_, _) => RemoveAnalysisAgent();
        agentButtons.Controls.Add(removeAgent);
        agentsLayout.Controls.Add(agentButtons, 0, 0);
        agentsLayout.Controls.Add(new Label
        {
            Text = "Gönderilecek tüm tam metinler aşağıda gösterilir; dosyalar yerel olarak değiştirilmez.",
            AutoSize = true,
            Anchor = AnchorStyles.Left,
            MaximumSize = new Size(600, 0)
        }, 1, 0);
        agentsLayout.Controls.Add(analysisAgentList, 0, 1);
        agentsLayout.Controls.Add(analysisPromptPreview, 1, 1);
        layout.Controls.Add(agentsGroup, 0, 0);

        var instructionGroup = new GroupBox
        {
            Text = "Analiz talimatı",
            Dock = DockStyle.Fill,
            Padding = new Padding(8)
        };
        instructionGroup.Controls.Add(analysisInstruction);
        layout.Controls.Add(instructionGroup, 0, 1);

        var consentLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2
        };
        consentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        consentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
        consentLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        consentLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        consentLayout.Controls.Add(analysisConsent, 0, 0);
        consentLayout.SetColumnSpan(analysisConsent, 2);
        consentLayout.Controls.Add(analyzeAgentsButton, 0, 1);
        consentLayout.Controls.Add(analysisStatus, 1, 1);
        layout.Controls.Add(consentLayout, 0, 2);

        var resultGroup = new GroupBox
        {
            Text = "Seçilen uzmanlar ve analiz sonucu",
            Dock = DockStyle.Fill,
            Padding = new Padding(8)
        };
        resultGroup.Controls.Add(analysisResult);
        layout.Controls.Add(resultGroup, 0, 3);

        analysisInstruction.MaxLength = AgentAnalysisSecurity.MaxInstructionCharacters;
        analysisPromptPreview.MaxLength = 64 * 1024;
        analysisConsent.CheckedChanged += (_, _) =>
            analyzeAgentsButton.Enabled = analysisConsent.Checked && analysisDocuments.Count > 0;
        analysisInstruction.TextChanged += (_, _) => ResetAnalysisConsent();
        planningUrl.TextChanged += (_, _) => ResetAnalysisConsent();
        apiKey.TextChanged += (_, _) => ResetAnalysisConsent();
        analyzeAgentsButton.Click += async (_, _) => await AnalyzeAgentsAsync();
        tabs.TabPages.Add(page);
    }

    private void AddAnalysisAgents()
    {
        using var picker = new OpenFileDialog
        {
            Title = "Claude'un değerlendirmesi için .md ajan tanımlarını seçin",
            Filter = "Markdown ajan dosyaları (*.md)|*.md",
            DefaultExt = "md",
            CheckFileExists = true,
            Multiselect = true
        };
        if (picker.ShowDialog(this) != DialogResult.OK)
            return;

        try
        {
            var additions = new List<(string Path, AgentAnalysisAgent Agent)>();
            var knownPaths = new HashSet<string>(
                analysisDocuments.Select(document => document.Path),
                StringComparer.OrdinalIgnoreCase);
            var totalPromptBytes = analysisDocuments.Sum(document =>
                Encoding.UTF8.GetByteCount(document.Agent.prompt));
            foreach (var selectedPath in picker.FileNames)
            {
                var path = Path.GetFullPath(selectedPath);
                if (!string.Equals(Path.GetExtension(path), ".md", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("Yalnızca .md ajan dosyaları seçilebilir.");
                if (!knownPaths.Add(path))
                    continue;
                if (analysisDocuments.Count + additions.Count >= AgentAnalysisSecurity.MaxAgents)
                    throw new InvalidDataException($"En fazla {AgentAnalysisSecurity.MaxAgents} ajan seçilebilir.");

                var bytes = ReadAgentPromptBytes(path);
                var prompt = new UTF8Encoding(false, true).GetString(bytes);
                var promptBytes = Encoding.UTF8.GetByteCount(prompt);
                if (string.IsNullOrWhiteSpace(prompt) || prompt.Contains('\0'))
                    throw new InvalidDataException("Ajan dosyası boş veya desteklenmeyen içerik taşıyor.");
                if (promptBytes > AgentAnalysisSecurity.MaxPromptBytes)
                    throw new InvalidDataException(
                        $"Her ajan dosyası en fazla {AgentAnalysisSecurity.MaxPromptBytes / 1024} KiB olabilir.");
                totalPromptBytes += promptBytes;
                if (totalPromptBytes > AgentAnalysisSecurity.MaxTotalPromptBytes)
                    throw new InvalidDataException(
                        $"Seçilen tüm ajan metinlerinin toplamı {AgentAnalysisSecurity.MaxTotalPromptBytes / 1024} KiB sınırını aşamaz.");

                var name = Path.GetFileNameWithoutExtension(path);
                if (string.IsNullOrWhiteSpace(name)
                    || name.Length > AgentAnalysisSecurity.MaxAgentNameCharacters
                    || name.Any(character => character < 32 || character == 127))
                    throw new InvalidDataException("Ajan dosyasının adı geçerli uzunlukta olmalıdır.");
                additions.Add((path, new AgentAnalysisAgent
                {
                    id = $"agent-{analysisDocuments.Count + additions.Count + 1}",
                    name = name,
                    prompt = prompt
                }));
            }

            analysisDocuments.AddRange(additions);
            RefreshAnalysisAgentPreview();
        }
        catch (InvalidDataException error)
        {
            analysisStatus.Text = error.Message;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            analysisStatus.Text = error.Message;
        }
    }

    private static byte[] ReadAgentPromptBytes(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (stream.Length > AgentAnalysisSecurity.MaxPromptBytes)
            throw new InvalidDataException(
                $"Her ajan dosyası en fazla {AgentAnalysisSecurity.MaxPromptBytes / 1024} KiB olabilir.");

        var buffer = new byte[AgentAnalysisSecurity.MaxPromptBytes + 1];
        var length = 0;
        while (length < buffer.Length)
        {
            var read = stream.Read(buffer, length, buffer.Length - length);
            if (read == 0)
                break;
            length += read;
        }
        if (length > AgentAnalysisSecurity.MaxPromptBytes || stream.ReadByte() != -1)
            throw new InvalidDataException(
                $"Her ajan dosyası en fazla {AgentAnalysisSecurity.MaxPromptBytes / 1024} KiB olabilir.");
        return buffer[..length];
    }

    private void RemoveAnalysisAgent()
    {
        var index = analysisAgentList.SelectedIndex;
        if (index < 0 || index >= analysisDocuments.Count)
            return;
        analysisDocuments.RemoveAt(index);
        for (var item = 0; item < analysisDocuments.Count; item++)
            analysisDocuments[item] = (
                analysisDocuments[item].Path,
                new AgentAnalysisAgent
                {
                    id = $"agent-{item + 1}",
                    name = analysisDocuments[item].Agent.name,
                    prompt = analysisDocuments[item].Agent.prompt
                });
        RefreshAnalysisAgentPreview();
    }

    private void RefreshAnalysisAgentPreview()
    {
        ResetAnalysisConsent();
        analysisAgentList.Items.Clear();
        var preview = new StringBuilder();
        foreach (var document in analysisDocuments)
        {
            analysisAgentList.Items.Add(document.Agent.name);
            preview.Append("=== ");
            preview.Append(document.Agent.name);
            preview.Append(" (");
            preview.Append(Path.GetFileName(document.Path));
            preview.AppendLine(") ===");
            preview.Append(document.Agent.prompt);
            if (!document.Agent.prompt.EndsWith('\n'))
                preview.AppendLine();
            preview.AppendLine();
        }
        analysisPromptPreview.Text = preview.ToString();
        analysisStatus.Text = analysisDocuments.Count == 0
            ? "Ajan seçin ve Claude'a gönderilecek tam metinleri inceleyin."
            : $"{analysisDocuments.Count} ajan seçildi; tüm tam metinleri inceleyin ve açık onay verin.";
        analysisResult.Clear();
    }

    private void ResetAnalysisConsent()
    {
        if (analysisConsent.Checked)
            analysisConsent.Checked = false;
        analyzeAgentsButton.Enabled = false;
        analysisResult.Clear();
    }

    private async Task AnalyzeAgentsAsync()
    {
        if (!analysisConsent.Checked)
            return;

        analysisConsent.Checked = false;
        analyzeAgentsButton.Enabled = false;
        try
        {
            var url = TransferSecurity.ValidateServerUrl(planningUrl.Text);
            var key = apiKey.Text;
            var candidates = analysisDocuments.Select(document => document.Agent).ToList();
            var request = AgentAnalysisSecurity.CreateRequest(
                analysisInstruction.Text,
                candidates,
                userConsent: true);

            analysisStatus.Text = "Analiz için yalnızca talimat ve önizlemede gösterilen ajan metinleri gönderiliyor...";
            using var client = new AgentAnalysisClient(url, key);
            var response = await client.AnalyzeAsync(
                request.instruction,
                request.agents,
                userConsent: true,
                lifetime.Token);
            if (IsDisposed || lifetime.IsCancellationRequested)
                return;

            var result = new StringBuilder();
            result.AppendLine("Claude'un seçtiği uzmanlar:");
            if (response.selected_agents.Count == 0)
                result.AppendLine("İstek için uygun uzman bulunamadı.");
            else
                foreach (var agent in response.selected_agents)
                    result.AppendLine($"- {agent.name}");

            foreach (var analysis in response.analyses)
            {
                var selectedAgent = response.selected_agents.Single(agent => agent.id == analysis.agent_id);
                result.AppendLine();
                result.AppendLine($"--- {selectedAgent.name} ---");
                result.AppendLine(analysis.analysis);
            }

            result.AppendLine();
            result.AppendLine("Birleştirilmiş değerlendirme:");
            result.AppendLine(response.synthesis);
            analysisResult.Text = result.ToString();
            analysisStatus.Text = "Analiz tamamlandı. Sonuçlar yalnızca bu pencerenin geçici arayüz durumunda tutuluyor.";
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error) when (error is ArgumentException or InvalidDataException)
        {
            analysisStatus.Text = error.Message;
        }
        catch
        {
            analysisStatus.Text = "Analiz tamamlanamadı. HTTPS sunucu adresini ve API anahtarını kontrol edip yeniden deneyin.";
        }
        finally
        {
            analyzeAgentsButton.Enabled = analysisConsent.Checked && analysisDocuments.Count > 0;
        }
    }

    private void BuildServerTab()
    {
        var page = new TabPage("Yerel sunucu");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 1
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 235));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        page.Controls.Add(layout);

        var profileGroup = new GroupBox { Text = "Yerel profiller", Dock = DockStyle.Fill, Padding = new Padding(8) };
        profileGroup.Controls.Add(profiles);
        var profileButtons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 38 };
        profileGroup.Controls.Add(profileButtons);
        var newProfile = new Button { Text = "Yeni profil" };
        newProfile.Click += (_, _) => NewProfile();
        profileButtons.Controls.Add(newProfile);
        var deleteProfile = new Button { Text = "Sil" };
        deleteProfile.Click += (_, _) => DeleteProfile();
        profileButtons.Controls.Add(deleteProfile);
        profiles.SelectedIndexChanged += (_, _) => LoadSelectedProfile();
        layout.Controls.Add(profileGroup, 0, 0);

        var detailGroup = new GroupBox { Text = "Yalnızca seçili klasörde çalışan yerel profil", Dock = DockStyle.Fill, Padding = new Padding(8) };
        var detail = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 7 };
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 100));
        for (var row = 0; row < 5; row++)
            detail.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        detail.RowStyles.Add(new RowStyle(SizeType.Absolute, 76));
        detail.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        detailGroup.Controls.Add(detail);

        detail.Controls.Add(new Label { Text = "Profil adı", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        detail.Controls.Add(profileName, 1, 0);
        detail.SetColumnSpan(profileName, 2);
        detail.Controls.Add(new Label { Text = "Çalıştırılabilir", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        detail.Controls.Add(executablePath, 1, 1);
        var chooseExe = new Button { Text = "Göz at...", Dock = DockStyle.Fill };
        chooseExe.Click += (_, _) => ChooseExecutable();
        detail.Controls.Add(chooseExe, 2, 1);
        detail.Controls.Add(new Label { Text = "Çalışma klasörü", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 2);
        detail.Controls.Add(workingDirectory, 1, 2);
        var chooseWorkingDirectory = new Button { Text = "Göz at...", Dock = DockStyle.Fill };
        chooseWorkingDirectory.Click += (_, _) => ChooseWorkingDirectory();
        detail.Controls.Add(chooseWorkingDirectory, 2, 2);
        detail.Controls.Add(new Label { Text = "Sabit bağımsız değişkenler", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 3);
        detail.Controls.Add(arguments, 1, 3);
        detail.SetColumnSpan(arguments, 2);
        var save = new Button { Text = "Profili kaydet", Dock = DockStyle.Fill };
        save.Click += (_, _) => SaveProfile();
        detail.Controls.Add(save, 0, 4);
        detail.SetColumnSpan(save, 3);

        var serverActions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = true };
        detail.Controls.Add(serverActions, 0, 5);
        detail.SetColumnSpan(serverActions, 3);
        var start = new Button { Text = "Başlat", AutoSize = true };
        start.Click += (_, _) => StartServer();
        serverActions.Controls.Add(start);
        var stop = new Button { Text = "Durdur", AutoSize = true };
        stop.Click += (_, _) => StopServerFromUi();
        serverActions.Controls.Add(stop);
        var restart = new Button { Text = "Yeniden başlat", AutoSize = true };
        restart.Click += (_, _) => RestartServer();
        serverActions.Controls.Add(restart);
        serverActions.Controls.Add(new Label
        {
            Text = "Yalnızca bu bilgisayarda tanımlı profili etkiler; bulut sunucusu yönetilmez.",
            AutoSize = true,
            MaximumSize = new Size(440, 0),
            Padding = new Padding(3, 7, 0, 0)
        });

        var statusLog = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2 };
        statusLog.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        statusLog.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        statusLog.Controls.Add(serverStatus, 0, 0);
        statusLog.Controls.Add(serverLog, 0, 1);
        detail.Controls.Add(statusLog, 0, 6);
        detail.SetColumnSpan(statusLog, 3);
        layout.Controls.Add(detailGroup, 1, 0);
        tabs.TabPages.Add(page);
    }

    private void BuildWorkflowTab()
    {
        var page = new TabPage("Derleme/Test");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 1
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 235));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        page.Controls.Add(layout);

        var workflowGroup = new GroupBox { Text = "Sabit proje iş akışları", Dock = DockStyle.Fill, Padding = new Padding(8) };
        workflowGroup.Controls.Add(workflows);
        var workflowButtons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 38 };
        workflowGroup.Controls.Add(workflowButtons);
        var newWorkflow = new Button { Text = "Yeni iş akışı" };
        newWorkflow.Click += (_, _) => NewWorkflow();
        workflowButtons.Controls.Add(newWorkflow);
        var deleteWorkflow = new Button { Text = "Sil" };
        deleteWorkflow.Click += (_, _) => DeleteWorkflow();
        workflowButtons.Controls.Add(deleteWorkflow);
        workflows.SelectedIndexChanged += (_, _) => LoadSelectedWorkflow();
        layout.Controls.Add(workflowGroup, 0, 0);

        var detailGroup = new GroupBox { Text = "Kullanıcı tarafından tanımlanmış sabit komut", Dock = DockStyle.Fill, Padding = new Padding(8) };
        var detail = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 8 };
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140));
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        detail.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 100));
        for (var row = 0; row < 5; row++)
            detail.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        detail.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        detail.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        detail.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        detailGroup.Controls.Add(detail);

        workflowKind.Items.AddRange(["Derleme", "Test"]);
        workflowKind.SelectedIndex = 1;
        workflowName.MaxLength = 50;
        workflowArguments.MaxLength = 4096;
        detail.Controls.Add(new Label { Text = "İş akışı türü", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        detail.Controls.Add(workflowKind, 1, 0);
        detail.SetColumnSpan(workflowKind, 2);
        detail.Controls.Add(new Label { Text = "Ad", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        detail.Controls.Add(workflowName, 1, 1);
        detail.SetColumnSpan(workflowName, 2);
        detail.Controls.Add(new Label { Text = "Çalıştırılabilir", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 2);
        detail.Controls.Add(workflowExecutable, 1, 2);
        var chooseExecutable = new Button { Text = "Göz at...", Dock = DockStyle.Fill };
        chooseExecutable.Click += (_, _) => ChooseWorkflowExecutable();
        detail.Controls.Add(chooseExecutable, 2, 2);
        detail.Controls.Add(new Label { Text = "Çalışma klasörü", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 3);
        detail.Controls.Add(workflowWorkingDirectory, 1, 3);
        var chooseDirectory = new Button { Text = "Göz at...", Dock = DockStyle.Fill };
        chooseDirectory.Click += (_, _) => ChooseWorkflowWorkingDirectory();
        detail.Controls.Add(chooseDirectory, 2, 3);
        detail.Controls.Add(new Label { Text = "Sabit bağımsız değişkenler", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 4);
        detail.Controls.Add(workflowArguments, 1, 4);
        detail.SetColumnSpan(workflowArguments, 2);
        var save = new Button { Text = "İş akışını kaydet", Dock = DockStyle.Fill };
        save.Click += (_, _) => SaveWorkflow();
        detail.Controls.Add(save, 0, 5);
        detail.SetColumnSpan(save, 3);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = true };
        detail.Controls.Add(actions, 0, 6);
        detail.SetColumnSpan(actions, 3);
        var run = new Button { Text = "Seçili iş akışını çalıştır", AutoSize = true };
        run.Click += (_, _) => RunWorkflow();
        actions.Controls.Add(run);
        var stop = new Button { Text = "Durdur", AutoSize = true };
        stop.Click += (_, _) => StopWorkflow();
        actions.Controls.Add(stop);
        actions.Controls.Add(new Label
        {
            Text = "Komut kabuğu yok; yalnızca kaydedilmiş Build/Test profili açıkça çalıştırılır.",
            AutoSize = true,
            MaximumSize = new Size(420, 0),
            Padding = new Padding(3, 7, 0, 0)
        });

        var statusLog = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2 };
        statusLog.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        statusLog.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        statusLog.Controls.Add(workflowStatus, 0, 0);
        statusLog.Controls.Add(workflowLog, 0, 1);
        detail.Controls.Add(statusLog, 0, 7);
        detail.SetColumnSpan(statusLog, 3);
        layout.Controls.Add(detailGroup, 1, 0);
        tabs.TabPages.Add(page);
    }

    private void LoadWorkspaceState()
    {
        try
        {
            if (state.WorkspaceRoot.Length > 0)
            {
                state.WorkspaceRoot = WorkspaceSecurity.ValidateWorkspaceRoot(state.WorkspaceRoot);
                workspacePath.Text = state.WorkspaceRoot;
                foreach (var path in state.WorkspaceFiles.ToArray())
                {
                    try
                    {
                        WorkspaceSecurity.ValidateExistingWorkspaceFile(state.WorkspaceRoot, path);
                        if (!workspaceFiles.Items.Contains(path))
                            workspaceFiles.Items.Add(path);
                    }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException
                                                  or ArgumentException or NotSupportedException or InvalidDataException)
                    {
                        state.WorkspaceFiles.Remove(path);
                        Report($"Önceden seçilen dosya kullanılamıyor ve seçimden çıkarıldı: {path} ({error.Message})");
                    }
                }
            }
        }
        catch (Exception error)
        {
            state.WorkspaceRoot = "";
            state.WorkspaceFiles.Clear();
            workspacePath.Clear();
            Report("Kaydedilmiş çalışma alanı artık kullanılamıyor: " + error.Message);
        }
    }

    private void ChooseWorkspaceRoot()
    {
        using var picker = new FolderBrowserDialog
        {
            Description = "Çip'in planlama ve yerel sunucu profilleri için kullanacağı proje klasörünü seçin.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
            SelectedPath = Directory.Exists(workspacePath.Text) ? workspacePath.Text : ""
        };
        if (picker.ShowDialog(this) != DialogResult.OK)
            return;
        try
        {
            var selected = WorkspaceSecurity.ValidateWorkspaceRoot(picker.SelectedPath);
            if (!string.Equals(selected, state.WorkspaceRoot, StringComparison.OrdinalIgnoreCase))
            {
                state.WorkspaceRoot = selected;
                state.WorkspaceFiles.Clear();
                workspaceFiles.Items.Clear();
                SaveSettings();
            }
            workspacePath.Text = selected;
            Report("Çalışma alanı seçildi. Yalnızca ayrıca eklediğiniz dosyalar planlama isteğine gönderilir.");
        }
        catch (Exception error)
        {
            Report("Çalışma alanı seçilemedi: " + error.Message);
        }
    }

    private void ClearWorkspaceRoot()
    {
        if (state.WorkspaceRoot.Length == 0)
            return;
        if (MessageBox.Show(this, "Çalışma alanı ve seçili dosyalar temizlensin mi?", "Çalışma alanını temizle",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        state.WorkspaceRoot = "";
        state.WorkspaceFiles.Clear();
        workspacePath.Clear();
        workspaceFiles.Items.Clear();
        SaveSettings();
        Report("Çalışma alanı temizlendi.");
    }

    private void AddWorkspaceFiles()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            Report("Önce çalışma alanı klasörünü seçin.");
            return;
        }

        using var picker = new OpenFileDialog
        {
            Title = "Planlama için mevcut dosyaları açıkça seçin",
            InitialDirectory = state.WorkspaceRoot,
            CheckFileExists = true,
            Multiselect = true
        };
        if (picker.ShowDialog(this) != DialogResult.OK)
            return;
        try
        {
            foreach (var fullPath in picker.FileNames)
            {
                var canonical = Path.GetFullPath(fullPath);
                if (!TransferSecurity.IsPathAllowed(canonical, [state.WorkspaceRoot]))
                    throw new UnauthorizedAccessException("Yalnızca seçili çalışma alanındaki dosyalar eklenebilir.");
                var relative = Path.GetRelativePath(state.WorkspaceRoot, canonical)
                    .Replace(Path.DirectorySeparatorChar, '/')
                    .Replace(Path.AltDirectorySeparatorChar, '/');
                WorkspaceSecurity.ValidateExistingWorkspaceFile(state.WorkspaceRoot, relative);
                _ = WorkspaceSecurity.ReadUtf8File(state.WorkspaceRoot, relative);
                if (!workspaceFiles.Items.Contains(relative))
                {
                    if (workspaceFiles.Items.Count >= WorkspaceSecurity.MaxFiles)
                        throw new InvalidOperationException($"En fazla {WorkspaceSecurity.MaxFiles} dosya seçilebilir.");
                    workspaceFiles.Items.Add(relative);
                    state.WorkspaceFiles.Add(relative);
                }
            }
            SaveSettings();
            Report($"{workspaceFiles.Items.Count} seçili dosya çalışma alanında tutuluyor.");
        }
        catch (Exception error)
        {
            SaveSettings();
            Report("Dosya eklenemedi: " + error.Message);
        }
    }

    private void RemoveWorkspaceFile()
    {
        if (workspaceFiles.SelectedItem is not string path)
            return;
        workspaceFiles.Items.Remove(path);
        state.WorkspaceFiles.RemoveAll(item => string.Equals(item, path, StringComparison.Ordinal));
        SaveSettings();
        Report($"Planlama seçiminden çıkarıldı: {path}");
    }

    private async Task CreatePlanAsync()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            Report("Önce çalışma alanı klasörünü seçin.");
            return;
        }
        createPlanButton.Enabled = false;
        try
        {
            var url = TransferSecurity.ValidateServerUrl(planningUrl.Text);
            var key = apiKey.Text;
            var safeInstruction = WorkspaceSecurity.ValidateInstruction(instruction.Text);
            var files = WorkspaceSecurity.ReadSelectedFiles(state.WorkspaceRoot, state.WorkspaceFiles);
            var serializedSize = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
                new WorkspacePlanRequest { instruction = safeInstruction, files = files.ToList() }).Length;
            if (serializedSize > WorkspaceSecurity.MaxRequestBytes)
                throw new InvalidDataException("Plan isteği 512 KiB sınırını aşıyor.");

            state.PlanningServerUrl = url;
            state.PlanningApiKey = key;
            SaveSettings();
            using var client = new WorkspacePlanClient(url, key);
            Report("Plan oluşturuluyor. Sunucuya yalnızca talimat ve açıkça seçtiğiniz dosyalar gönderilir.");
            var plan = await client.CreatePlanAsync(safeInstruction, files, lifetime.Token);
            if (IsDisposed || lifetime.IsCancellationRequested)
                return;
            using var review = new WorkspacePlanReviewForm(state.WorkspaceRoot, files, plan);
            if (review.ShowDialog(this) == DialogResult.OK)
                Report(review.ResultMessage);
            else
                Report("Plan incelendi; dosyalarda değişiklik yapılmadı.");
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            Report("Plan oluşturulamadı: " + error.Message);
        }
        finally
        {
            createPlanButton.Enabled = true;
        }
    }

    private void NewProfile()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            Report("Yerel sunucu profili için önce çalışma alanı seçin.");
            return;
        }
        profiles.ClearSelected();
        profileName.Clear();
        executablePath.Clear();
        workingDirectory.Text = state.WorkspaceRoot;
        arguments.Clear();
        profileName.Focus();
    }

    private void DeleteProfile()
    {
        if (profiles.SelectedItem is not ServerProfile selected)
            return;
        if (MessageBox.Show(this, $"“{selected.Name}” yerel profili silinsin mi?", "Profili sil",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        state.ServerProfiles.Remove(selected);
        SaveSettings();
        RefreshProfiles();
        Report($"Yerel profil silindi: {selected.Name}");
    }

    private void LoadSelectedProfile()
    {
        if (profiles.SelectedItem is not ServerProfile profile)
            return;
        profileName.Text = profile.Name;
        executablePath.Text = profile.ExecutablePath;
        workingDirectory.Text = profile.WorkingDirectory;
        arguments.Text = profile.Arguments;
    }

    private void ChooseExecutable()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            Report("Önce çalışma alanı seçin.");
            return;
        }
        using var picker = new OpenFileDialog
        {
            Title = "Yerel sunucu .exe dosyasını seçin",
            InitialDirectory = state.WorkspaceRoot,
            CheckFileExists = true,
            Multiselect = false,
            Filter = "Windows uygulamaları (*.exe)|*.exe"
        };
        if (picker.ShowDialog(this) == DialogResult.OK)
            executablePath.Text = Path.GetFullPath(picker.FileName);
    }

    private void ChooseWorkingDirectory()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            Report("Önce çalışma alanı seçin.");
            return;
        }
        using var picker = new FolderBrowserDialog
        {
            Description = "Yerel sunucunun çalışma klasörünü seçin.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
            SelectedPath = Directory.Exists(workingDirectory.Text) ? workingDirectory.Text : state.WorkspaceRoot
        };
        if (picker.ShowDialog(this) == DialogResult.OK)
            workingDirectory.Text = Path.GetFullPath(picker.SelectedPath);
    }

    private void SaveProfile()
    {
        try
        {
            if (state.WorkspaceRoot.Length == 0)
                throw new InvalidOperationException("Önce çalışma alanı seçin.");
            var selected = profiles.SelectedItem as ServerProfile;
            var profile = new ServerProfile
            {
                Name = profileName.Text.Trim(),
                WorkspaceRoot = WorkspaceSecurity.ValidateWorkspaceRoot(state.WorkspaceRoot),
                ExecutablePath = Path.GetFullPath(executablePath.Text),
                WorkingDirectory = Path.GetFullPath(workingDirectory.Text),
                Arguments = arguments.Text
            };
            WorkspaceSecurity.ValidateServerProfile(profile, state.WorkspaceRoot);
            if (selected is null)
                state.ServerProfiles.Add(profile);
            else
            {
                var index = state.ServerProfiles.IndexOf(selected);
                if (index < 0)
                    throw new InvalidOperationException("Seçili profil kayıtlı profiller arasında bulunamadı.");
                state.ServerProfiles[index] = profile;
            }
            SaveSettings();
            RefreshProfiles(profile);
            Report($"“{profile.Name}” profili kaydedildi. Başlatma yalnızca açıkça Başlat'a basınca yapılır.");
        }
        catch (Exception error)
        {
            Report("Profil kaydedilemedi: " + error.Message);
        }
    }

    private void RefreshProfiles(ServerProfile? selectedProfile = null)
    {
        profiles.Items.Clear();
        foreach (var profile in state.ServerProfiles)
            profiles.Items.Add(profile);
        if (selectedProfile is not null)
            profiles.SelectedItem = selectedProfile;
        else if (profiles.Items.Count > 0 && profiles.SelectedIndex < 0)
            profiles.SelectedIndex = 0;
    }

    private void NewWorkflow()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            ReportWorkflow("Derleme/Test iş akışı için önce çalışma alanı seçin.");
            return;
        }
        workflows.ClearSelected();
        workflowKind.SelectedIndex = 1;
        workflowName.Clear();
        workflowExecutable.Clear();
        workflowWorkingDirectory.Text = state.WorkspaceRoot;
        workflowArguments.Clear();
        workflowName.Focus();
    }

    private void DeleteWorkflow()
    {
        if (workflows.SelectedItem is not ProjectWorkflow selected)
            return;
        if (MessageBox.Show(this, $"“{selected.Name}” iş akışı silinsin mi?", "İş akışını sil",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        state.ProjectWorkflows.Remove(selected);
        SaveSettings();
        RefreshWorkflows();
        ReportWorkflow($"İş akışı silindi: {selected.Name}");
    }

    private void LoadSelectedWorkflow()
    {
        if (workflows.SelectedItem is not ProjectWorkflow workflow)
            return;
        workflowKind.SelectedIndex = workflow.Kind == "build" ? 0 : 1;
        workflowName.Text = workflow.Name;
        workflowExecutable.Text = workflow.ExecutablePath;
        workflowWorkingDirectory.Text = workflow.WorkingDirectory;
        workflowArguments.Text = workflow.Arguments;
    }

    private void ChooseWorkflowExecutable()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            ReportWorkflow("Önce çalışma alanı seçin.");
            return;
        }
        using var picker = new OpenFileDialog
        {
            Title = "Sabit Build/Test yürütücüsünü seçin",
            InitialDirectory = state.WorkspaceRoot,
            CheckFileExists = true,
            Multiselect = false,
            Filter = "Windows uygulamaları (*.exe)|*.exe"
        };
        if (picker.ShowDialog(this) == DialogResult.OK)
            workflowExecutable.Text = Path.GetFullPath(picker.FileName);
    }

    private void ChooseWorkflowWorkingDirectory()
    {
        if (state.WorkspaceRoot.Length == 0)
        {
            ReportWorkflow("Önce çalışma alanı seçin.");
            return;
        }
        using var picker = new FolderBrowserDialog
        {
            Description = "İş akışının çalışma klasörünü seçin.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
            SelectedPath = Directory.Exists(workflowWorkingDirectory.Text)
                ? workflowWorkingDirectory.Text
                : state.WorkspaceRoot
        };
        if (picker.ShowDialog(this) == DialogResult.OK)
            workflowWorkingDirectory.Text = Path.GetFullPath(picker.SelectedPath);
    }

    private void SaveWorkflow()
    {
        try
        {
            if (state.WorkspaceRoot.Length == 0)
                throw new InvalidOperationException("Önce çalışma alanı seçin.");
            var selected = workflows.SelectedItem as ProjectWorkflow;
            var workflow = new ProjectWorkflow
            {
                Name = workflowName.Text.Trim(),
                Kind = workflowKind.SelectedIndex == 0 ? "build" : "test",
                WorkspaceRoot = WorkspaceSecurity.ValidateWorkspaceRoot(state.WorkspaceRoot),
                ExecutablePath = Path.GetFullPath(workflowExecutable.Text),
                WorkingDirectory = Path.GetFullPath(workflowWorkingDirectory.Text),
                Arguments = workflowArguments.Text
            };
            WorkspaceSecurity.ValidateProjectWorkflow(workflow, state.WorkspaceRoot);
            if (selected is null)
                state.ProjectWorkflows.Add(workflow);
            else
            {
                var index = state.ProjectWorkflows.IndexOf(selected);
                if (index < 0)
                    throw new InvalidOperationException("Seçili iş akışı kayıtlı profiller arasında bulunamadı.");
                state.ProjectWorkflows[index] = workflow;
            }
            SaveSettings();
            RefreshWorkflows(workflow);
            ReportWorkflow($"“{workflow.Name}” {workflow.Kind} iş akışı kaydedildi.");
        }
        catch (Exception error)
        {
            ReportWorkflow("İş akışı kaydedilemedi: " + error.Message);
        }
    }

    private void RefreshWorkflows(ProjectWorkflow? selectedWorkflow = null)
    {
        workflows.Items.Clear();
        foreach (var workflow in state.ProjectWorkflows)
            workflows.Items.Add(workflow);
        if (selectedWorkflow is not null)
            workflows.SelectedItem = selectedWorkflow;
        else if (workflows.Items.Count > 0 && workflows.SelectedIndex < 0)
            workflows.SelectedIndex = 0;
    }

    private void RunWorkflow()
    {
        try
        {
            if (workflows.SelectedItem is not ProjectWorkflow workflow)
                throw new InvalidOperationException("Çalıştırmak için kayıtlı Build veya Test iş akışı seçin.");
            if (!string.Equals(workflow.WorkspaceRoot, state.WorkspaceRoot, StringComparison.OrdinalIgnoreCase))
                throw new UnauthorizedAccessException("İş akışının çalışma alanı artık seçili değil.");
            WorkspaceSecurity.ValidateProjectWorkflow(workflow, state.WorkspaceRoot);
            LaunchConfiguredProcess(
                workflow.ExecutablePath,
                workflow.WorkingDirectory,
                workflow.Arguments,
                $"{workflow.Name} ({workflow.Kind})",
                "workflow",
                workflowLog,
                workflowStatus);
        }
        catch (Exception error)
        {
            ReportWorkflow("İş akışı başlatılamadı: " + error.Message);
        }
    }

    private void StopWorkflow()
    {
        if (managedProcessKind != "workflow")
        {
            ReportWorkflow("Bu pencerede çalışan bir Build/Test iş akışı yok.");
            return;
        }
        try
        {
            StopManagedProcess();
        }
        catch (Exception error)
        {
            ReportWorkflow("İş akışı durdurulamadı: " + error.Message);
        }
    }

    private void StartServer()
    {
        try
        {
            if (profiles.SelectedItem is not ServerProfile profile)
                throw new InvalidOperationException("Başlatmak için kayıtlı bir yerel profil seçin.");
            if (!string.Equals(profile.WorkspaceRoot, state.WorkspaceRoot, StringComparison.OrdinalIgnoreCase))
                throw new UnauthorizedAccessException("Profilin çalışma alanı artık seçili değil. Klasörü yeniden seçin.");
            WorkspaceSecurity.ValidateServerProfile(profile, state.WorkspaceRoot);
            LaunchConfiguredProcess(profile.ExecutablePath, profile.WorkingDirectory, profile.Arguments,
                profile.Name, "server", serverLog, serverStatus);
        }
        catch (Exception error)
        {
            Report("Yerel sunucu başlatılamadı: " + error.Message);
        }
    }

    private void StopServerFromUi()
    {
        if (managedProcessKind != "server")
        {
            Report("Bu pencerede çalışan bir yerel sunucu yok.");
            return;
        }
        try
        {
            StopManagedProcess();
        }
        catch (Exception error)
        {
            Report("Yerel sunucu durdurulamadı: " + error.Message);
        }
    }

    private void RestartServer()
    {
        try
        {
            if (managedProcess is { HasExited: false } && managedProcessKind == "workflow")
                throw new InvalidOperationException("Önce çalışan Build/Test iş akışını durdurun.");
            StopManagedProcess();
            StartServer();
        }
        catch (Exception error)
        {
            Report("Yerel sunucu yeniden başlatılamadı: " + error.Message);
        }
    }

    private void StopManagedProcess()
    {
        var process = managedProcess;
        if (process is null)
            return;
        if (!process.HasExited)
        {
            process.Kill(entireProcessTree: true);
            if (!process.WaitForExit(3000))
                throw new TimeoutException("Başlatılan yerel sunucu süreci 3 saniye içinde kapanmadı.");
        }
        managedProcess = null;
        var targetStatus = managedProcessStatus;
        var targetLog = managedProcessLog;
        var description = managedProcessDescription;
        managedProcessStatus = null;
        managedProcessLog = null;
        managedProcessDescription = "";
        managedProcessKind = "";
        process.Dispose();
        if (targetStatus is not null)
            targetStatus.Text = $"{description} durduruldu.";
        if (targetLog is not null)
            AppendLog(targetLog, $"Yalnızca bu pencerede başlatılan {description} süreci durduruldu.");
    }

    private void LaunchConfiguredProcess(
        string executable, string workingDirectoryPath, string fixedArguments,
        string description, string kind, RichTextBox output, Label status)
    {
        if (managedProcess is { HasExited: false })
            throw new InvalidOperationException("Bu pencerede başka bir yönetilen süreç zaten çalışıyor.");
        managedProcess?.Dispose();
        managedProcess = null;
        managedProcessStatus = null;
        managedProcessLog = null;
        managedProcessDescription = "";
        managedProcessKind = "";
        var startInfo = WorkspaceSecurity.CreateConfiguredProcessStartInfo(
            executable, workingDirectoryPath, fixedArguments);
        var process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, eventArgs) => AppendProcessOutput(output, "OUT", eventArgs.Data);
        process.ErrorDataReceived += (_, eventArgs) => AppendProcessOutput(output, "ERR", eventArgs.Data);
        process.Exited += (_, _) =>
        {
            if (IsDisposed || closing)
                return;
            BeginInvoke(new Action(() =>
            {
                if (ReferenceEquals(managedProcess, process))
                {
                    status.Text = $"{description} durdu (kod {process.ExitCode}).";
                    AppendLog(output, $"Süreç durdu (çıkış kodu {process.ExitCode}).");
                }
            }));
        };
        if (!process.Start())
        {
            process.Dispose();
            throw new InvalidOperationException("Yapılandırılmış süreç başlatılamadı.");
        }
        managedProcess = process;
        managedProcessStatus = status;
        managedProcessLog = output;
        managedProcessDescription = description;
        managedProcessKind = kind;
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        status.Text = $"{description} çalışıyor (PID {process.Id}).";
        AppendLog(output, $"Başlatıldı: {startInfo.FileName}");
    }

    private void AppendProcessOutput(RichTextBox output, string label, string? line)
    {
        if (line is null || IsDisposed || closing)
            return;
        try
        {
            BeginInvoke(new Action(() => AppendLog(output, $"{label}: {line}")));
        }
        catch (InvalidOperationException)
        {
        }
    }

    private void AppendLog(string message)
        => AppendLog(serverLog, message);

    private void AppendLog(RichTextBox output, string message)
    {
        if (output.IsDisposed)
            return;
        output.AppendText($"{DateTime.Now:HH:mm:ss} {message}{Environment.NewLine}");
        if (output.TextLength > 32_000)
        {
            output.Select(0, output.TextLength - 24_000);
            output.SelectedText = "";
        }
        output.SelectionStart = output.TextLength;
        output.ScrollToCaret();
    }

    private void SaveSettings()
    {
        state.PlanningServerUrl = planningUrl.Text.Trim();
        state.PlanningApiKey = apiKey.Text;
        state.WorkspaceFiles = workspaceFiles.Items.Cast<string>().ToList();
        saveState();
    }

    private void Report(string message)
    {
        serverStatus.Text = message;
        AppendLog(message);
    }

    private void ReportWorkflow(string message)
    {
        workflowStatus.Text = message;
        AppendLog(workflowLog, message);
    }
}

internal sealed class WorkspacePlanReviewForm : Form
{
    private readonly string workspaceRoot;
    private readonly string planSummary;
    private readonly Dictionary<string, string> originals;
    private readonly Dictionary<string, string> replacements;
    private readonly CheckedListBox files = new() { Dock = DockStyle.Fill, CheckOnClick = true };
    private readonly RichTextBox diff = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        WordWrap = false,
        DetectUrls = false,
        BackColor = SystemColors.Window,
        Font = new Font("Consolas", 9F)
    };
    private readonly TextBox applyConfirmation = new() { Width = 110 };
    private readonly Button applyButton = new() { Text = "Uygula", AutoSize = true, Height = 34, Enabled = false };

    public string ResultMessage { get; private set; } = "";

    public WorkspacePlanReviewForm(
        string workspaceRoot, IReadOnlyList<WorkspacePlanFile> submitted, WorkspacePlanResponse plan)
    {
        this.workspaceRoot = workspaceRoot;
        planSummary = plan.summary;
        originals = submitted.ToDictionary(file => file.path, file => file.content, StringComparer.Ordinal);
        replacements = plan.changes.ToDictionary(
            change => change.relative_path, change => change.replacement_content, StringComparer.Ordinal);

        Text = "Çip - Değişiklikleri incele";
        Width = 1000;
        Height = 720;
        MinimumSize = new Size(760, 560);
        StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Segoe UI", 9F);

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 3 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 260));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        Controls.Add(layout);
        var summary = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            Text = plan.summary,
            ScrollBars = ScrollBars.Vertical
        };
        layout.Controls.Add(summary, 0, 0);
        layout.SetColumnSpan(summary, 2);
        foreach (var file in submitted)
            files.Items.Add(file.path, true);
        files.SelectedIndexChanged += (_, _) => ShowSelectedDiff();
        layout.Controls.Add(files, 0, 1);
        var diffGroup = new GroupBox { Text = "Birleşik fark - eklenen satırlar +, kaldırılan satırlar -", Dock = DockStyle.Fill, Padding = new Padding(8) };
        diffGroup.Controls.Add(diff);
        layout.Controls.Add(diffGroup, 1, 1);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
        layout.Controls.Add(buttons, 0, 2);
        layout.SetColumnSpan(buttons, 2);
        var cancel = new Button { Text = "Vazgeç", AutoSize = true, Height = 34, DialogResult = DialogResult.Cancel };
        buttons.Controls.Add(cancel);
        applyButton.Click += (_, _) => ApplySelected();
        buttons.Controls.Add(applyButton);
        buttons.Controls.Add(applyConfirmation);
        buttons.Controls.Add(new Label { Text = "Uygulamak için Apply yazın:", AutoSize = true, Padding = new Padding(3, 10, 0, 0) });
        applyConfirmation.TextChanged += (_, _) =>
            applyButton.Enabled = string.Equals(applyConfirmation.Text, "Apply", StringComparison.Ordinal);
        AcceptButton = applyButton;
        CancelButton = cancel;
        if (files.Items.Count > 0)
            files.SelectedIndex = 0;
    }

    private void ShowSelectedDiff()
    {
        if (files.SelectedItem is not string path)
        {
            diff.Clear();
            return;
        }
        diff.Text = CreateUnifiedDiff(path, originals[path], replacements[path]);
        diff.SelectionStart = 0;
        diff.SelectionLength = 0;
    }

    private void ApplySelected()
    {
        var selectedPaths = files.CheckedItems.Cast<string>().ToList();
        if (selectedPaths.Count == 0)
        {
            MessageBox.Show(this, "Uygulamak için en az bir dosyayı işaretleyin.", "Dosya seçilmedi",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!string.Equals(applyConfirmation.Text, "Apply", StringComparison.Ordinal))
        {
            MessageBox.Show(this, "Değişiklikleri uygulamak için Apply yazın.", "Onay gerekli",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var applied = new List<string>();
        try
        {
            foreach (var path in selectedPaths)
            {
                WorkspaceSecurity.ValidateExistingWorkspaceFile(workspaceRoot, path);
                if (!string.Equals(WorkspaceSecurity.ReadUtf8File(workspaceRoot, path), originals[path], StringComparison.Ordinal))
                    throw new IOException($"“{path}” plan oluşturulduktan sonra değişti; hiçbir dosya bu işlemde yazılmadı. Yeni plan oluşturun.");
            }
            foreach (var path in selectedPaths)
            {
                WorkspaceSecurity.WriteReplacementAtomically(workspaceRoot, path, originals[path], replacements[path]);
                applied.Add(path);
            }
            ResultMessage = $"{planSummary} Uygulandı: {applied.Count} dosya.";
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception error)
        {
            var partial = applied.Count == 0 ? "" : $" Yazılmış dosyalar: {string.Join(", ", applied)}.";
            MessageBox.Show(this, $"Değişiklikler tam olarak uygulanamadı: {error.Message}{partial}",
                "Uygulama başarısız", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static string CreateUnifiedDiff(string path, string oldContent, string newContent)
    {
        static string[] Lines(string text) => text.Length == 0
            ? []
            : text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');

        var oldLines = Lines(oldContent);
        var newLines = Lines(newContent);
        var prefix = 0;
        while (prefix < oldLines.Length && prefix < newLines.Length
               && string.Equals(oldLines[prefix], newLines[prefix], StringComparison.Ordinal))
            prefix++;
        var suffix = 0;
        while (suffix < oldLines.Length - prefix && suffix < newLines.Length - prefix
               && string.Equals(oldLines[oldLines.Length - 1 - suffix],
                   newLines[newLines.Length - 1 - suffix], StringComparison.Ordinal))
            suffix++;

        var output = new StringBuilder();
        output.Append("--- a/").Append(path).AppendLine();
        output.Append("+++ b/").Append(path).AppendLine();
        output.Append("@@ -").Append(prefix + 1).Append(',').Append(oldLines.Length - prefix - suffix)
            .Append(" +").Append(prefix + 1).Append(',').Append(newLines.Length - prefix - suffix)
            .AppendLine(" @@");
        for (var index = 0; index < prefix; index++)
            output.Append(' ').AppendLine(oldLines[index]);
        for (var index = prefix; index < oldLines.Length - suffix; index++)
            output.Append('-').AppendLine(oldLines[index]);
        for (var index = prefix; index < newLines.Length - suffix; index++)
            output.Append('+').AppendLine(newLines[index]);
        for (var index = suffix; index > 0; index--)
            output.Append(' ').AppendLine(oldLines[oldLines.Length - index]);
        return output.ToString();
    }
}
