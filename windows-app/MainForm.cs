using System.ComponentModel;
using System.Security.Cryptography;

namespace Cip.Windows;

internal sealed class MainForm : Form
{
    private readonly LocalStateStore stateStore = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly System.Windows.Forms.Timer pollTimer = new() { Interval = 3000 };
    private readonly BindingList<TransferAction> actions = [];
    private readonly HashSet<string> uploading = [];
    private readonly HashSet<string> uploadFailed = [];
    private AppState state;
    private TextBox serverUrl = null!;
    private Label pairingStatus = null!;
    private Label pairingCode = null!;
    private ListBox folders = null!;
    private DataGridView queue = null!;
    private Label activity = null!;
    private Button pairButton = null!;
    private bool polling;

    public MainForm()
    {
        Text = "Çip - Windows dosya aktarımı";
        Width = 1040;
        Height = 760;
        MinimumSize = new Size(850, 620);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 9F);
        try
        {
            state = stateStore.Load();
        }
        catch (Exception error)
        {
            state = new AppState();
            MessageBox.Show(this, error.Message, "Ayarlar açılamadı", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        BuildScreen();
        UpdatePairingDisplay();
        pollTimer.Tick += async (_, _) => await RefreshAsync(false);
        pollTimer.Start();
        Shown += async (_, _) => await RefreshAsync(false);
        FormClosed += (_, _) =>
        {
            pollTimer.Stop();
            lifetime.Cancel();
            lifetime.Dispose();
        };
    }

    private void BuildScreen()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 1,
            RowCount = 4,
            AutoSize = false
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 164));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 35));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 65));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 82));
        Controls.Add(root);

        var connection = new GroupBox { Text = "Sunucu ve telefon eşleştirmesi", Dock = DockStyle.Fill, Padding = new Padding(10) };
        root.Controls.Add(connection, 0, 0);
        var connectionGrid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 4, RowCount = 3 };
        connectionGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        connectionGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        connectionGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
        connectionGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        connectionGrid.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        connectionGrid.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));
        connectionGrid.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        connection.Controls.Add(connectionGrid);
        connectionGrid.Controls.Add(new Label { Text = "HTTPS sunucu", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        serverUrl = new TextBox { Dock = DockStyle.Fill, PlaceholderText = "https://sunucu-adresiniz" };
        serverUrl.Text = state.ServerUrl;
        connectionGrid.Controls.Add(serverUrl, 1, 0);
        pairButton = new Button { Text = "Eşleştir", Dock = DockStyle.Fill };
        pairButton.Click += async (_, _) => await CreatePairingAsync();
        connectionGrid.Controls.Add(pairButton, 2, 0);
        var revoke = new Button { Text = "Eşleşmeyi iptal et", Dock = DockStyle.Fill };
        revoke.Click += async (_, _) => await RevokePairingAsync();
        connectionGrid.Controls.Add(revoke, 3, 0);

        pairingStatus = new Label { AutoSize = true, Anchor = AnchorStyles.Left, Text = "Henüz eşleşme yok." };
        connectionGrid.Controls.Add(pairingStatus, 0, 1);
        connectionGrid.SetColumnSpan(pairingStatus, 2);
        pairingCode = new Label
        {
            AutoSize = true,
            Anchor = AnchorStyles.Left,
            Font = new Font("Segoe UI", 18F, FontStyle.Bold),
            ForeColor = Color.DarkGreen
        };
        connectionGrid.Controls.Add(pairingCode, 2, 1);
        connectionGrid.SetColumnSpan(pairingCode, 2);

        var note = new Label
        {
            Dock = DockStyle.Fill,
            Text = "Eşleştir'e basın, ekrandaki 10 haneli kodu Android uygulamasına girin. Kod 5 dakika geçerlidir. Transfer uç noktalarında ASISTAN_API_KEY kullanılmaz.",
            AutoEllipsis = true
        };
        connectionGrid.Controls.Add(note, 0, 2);
        connectionGrid.SetColumnSpan(note, 4);

        var middle = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1 };
        middle.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 36));
        middle.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 64));
        root.Controls.Add(middle, 0, 1);

        var folderGroup = new GroupBox { Text = "İzin verilen klasörler", Dock = DockStyle.Fill, Padding = new Padding(8) };
        middle.Controls.Add(folderGroup, 0, 0);
        folders = new ListBox { Dock = DockStyle.Fill, IntegralHeight = false };
        folderGroup.Controls.Add(folders);
        var folderButtons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 38, FlowDirection = FlowDirection.LeftToRight };
        folderGroup.Controls.Add(folderButtons);
        var addFolder = new Button { Text = "Klasör ekle" };
        addFolder.Click += (_, _) => AddAllowedFolder();
        folderButtons.Controls.Add(addFolder);
        var removeFolder = new Button { Text = "Seçileni kaldır" };
        removeFolder.Click += (_, _) => RemoveAllowedFolder();
        folderButtons.Controls.Add(removeFolder);

        var transferGroup = new GroupBox { Text = "Dosya işlemleri", Dock = DockStyle.Fill, Padding = new Padding(8) };
        middle.Controls.Add(transferGroup, 1, 0);
        var transferButtons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            AutoScroll = true,
            Padding = new Padding(4)
        };
        transferGroup.Controls.Add(transferButtons);
        var send = new Button { Text = "Bilgisayardan telefona gönder", AutoSize = true, Height = 38 };
        send.Click += async (_, _) => await SendFileAsync();
        transferButtons.Controls.Add(send);
        var receive = new Button { Text = "Telefondan bilgisayara al", AutoSize = true, Height = 38 };
        receive.Click += async (_, _) => await ReceiveFileAsync();
        transferButtons.Controls.Add(receive);
        var selectSource = new Button { Text = "Onaylanan dosyayı yeniden seç", AutoSize = true, Height = 38 };
        selectSource.Click += async (_, _) => await ReattachApprovedSourceAsync();
        transferButtons.Controls.Add(selectSource);
        var cancel = new Button { Text = "Seçili işlemi iptal et", AutoSize = true, Height = 38 };
        cancel.Click += async (_, _) => await CancelSelectedAsync();
        transferButtons.Controls.Add(cancel);
        var copyCode = new Button { Text = "Eşleştirme kodunu kopyala", AutoSize = true, Height = 38 };
        copyCode.Click += (_, _) =>
        {
            if (!string.IsNullOrEmpty(pairingCode.Text))
                Clipboard.SetText(pairingCode.Text);
        };
        transferButtons.Controls.Add(copyCode);
        var transferNote = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(560, 0),
            Text = "Her işlem Android'de ayrı biyometrik onay bekler. Telefonda gönderilecek dosya Android uygulamasından seçilir; Windows dosyayı yalnızca onaylanıp hazır olduğunda alır."
        };
        transferButtons.Controls.Add(transferNote);

        var queueGroup = new GroupBox { Text = "İşlem kuyruğu - Android onayı ve aktarım durumu", Dock = DockStyle.Fill, Padding = new Padding(8) };
        root.Controls.Add(queueGroup, 0, 2);
        queue = new DataGridView
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AllowUserToResizeRows = false,
            AutoGenerateColumns = false,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            MultiSelect = false,
            RowHeadersVisible = false,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill
        };
        queue.Columns.Add(new DataGridViewTextBoxColumn { Name = "Id", HeaderText = "Kimlik", Visible = false });
        queue.Columns.Add(new DataGridViewTextBoxColumn { Name = "Direction", HeaderText = "Yön", FillWeight = 18 });
        queue.Columns.Add(new DataGridViewTextBoxColumn { Name = "Filename", HeaderText = "Dosya adı", FillWeight = 34 });
        queue.Columns.Add(new DataGridViewTextBoxColumn { Name = "Size", HeaderText = "Boyut", FillWeight = 14 });
        queue.Columns.Add(new DataGridViewTextBoxColumn { Name = "State", HeaderText = "Durum", FillWeight = 34 });
        queueGroup.Controls.Add(queue);

        activity = new Label { Dock = DockStyle.Fill, BorderStyle = BorderStyle.FixedSingle, Padding = new Padding(8), AutoEllipsis = true };
        root.Controls.Add(activity, 0, 3);
        RefreshFolders();
    }

    private async Task CreatePairingAsync()
    {
        try
        {
            var url = TransferSecurity.ValidateServerUrl(serverUrl.Text);
            if (!string.IsNullOrEmpty(state.PairingId))
            {
                MessageBox.Show(this, "Önce mevcut eşleşmeyi iptal edin.", "Eşleşme var",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            pairButton.Enabled = false;
            using var client = new TransferClient(url, "", "");
            var created = await client.CreatePairingAsync(lifetime.Token);
            if (created.pairing_id.Length == 0 || created.pc_token.Length == 0
                || created.pairing_code.Length != 10 || !created.pairing_code.All(char.IsAsciiDigit))
                throw new InvalidDataException("Sunucu eşleşme için gerekli bilgileri döndürmedi.");

            state.ServerUrl = url;
            state.PairingId = created.pairing_id;
            state.PcToken = created.pc_token;
            state.PairingCode = created.pairing_code;
            state.PairingCodeExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + created.pairing_code_expires_in;
            stateStore.Save(state);
            pairingCode.Text = state.PairingCode;
            UpdatePairingDisplay("Telefon bekleniyor. Bu kodu Android uygulamasına girin; kod 5 dakika içinde kullanılmalıdır.");
            Report("Yeni eşleşme oluşturuldu. Kod yalnızca bu bilgisayarda gösteriliyor.");
            await RefreshAsync(false);
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            Report("Eşleşme oluşturulamadı: " + error.Message);
        }
        finally
        {
            pairButton.Enabled = true;
        }
    }

    private async Task RevokePairingAsync()
    {
        if (string.IsNullOrEmpty(state.PairingId) || string.IsNullOrEmpty(state.PcToken))
            return;
        if (MessageBox.Show(this,
                "Bu eşleşme ve bekleyen dosya aktarımları iptal edilecek. Devam edilsin mi?",
                "Eşleşmeyi iptal et", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;

        try
        {
            using var client = NewClient();
            await client.RevokePairingAsync(lifetime.Token);
            ClearPairing("Eşleşme iptal edildi.");
            Report("Eşleşme ve bekleyen sunucu verileri iptal edildi.");
        }
        catch (HttpRequestException error) when (error.StatusCode == System.Net.HttpStatusCode.NotFound)
        {
            ClearPairing("Sunucuda eşleşme bulunamadı; yerel kayıt temizlendi.");
            Report("Sunucu eşleşmesi artık mevcut değil. Yerel kayıt temizlendi; yeniden eşleştirebilirsiniz.");
        }
        catch (Exception error)
        {
            Report("Eşleşme iptal edilemedi: " + error.Message);
        }
    }

    private void AddAllowedFolder()
    {
        using var picker = new FolderBrowserDialog
        {
            Description = "Çip'in erişmesine izin vereceğiniz klasörü seçin.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false
        };
        if (picker.ShowDialog(this) != DialogResult.OK)
            return;
        try
        {
            var path = Path.GetFullPath(picker.SelectedPath);
            if (!Directory.Exists(path))
                throw new DirectoryNotFoundException("Seçilen klasör bulunamadı.");
            TransferSecurity.EnsureNoReparsePoints(path);
            if (!state.AllowedFolders.Contains(path, StringComparer.OrdinalIgnoreCase))
            {
                state.AllowedFolders.Add(path);
                SaveState();
                RefreshFolders();
            }
        }
        catch (Exception error)
        {
            Report("Klasör eklenemedi: " + error.Message);
        }
    }

    private void RemoveAllowedFolder()
    {
        if (folders.SelectedItem is not string selected)
            return;
        state.AllowedFolders.RemoveAll(path => string.Equals(path, selected, StringComparison.OrdinalIgnoreCase));
        SaveState();
        RefreshFolders();
    }

    private async Task SendFileAsync()
    {
        try
        {
            EnsurePaired();
            var active = actions.Count(action => IsActive(action.state));
            if (active >= TransferSecurity.MaxActiveActions)
                throw new InvalidOperationException("Eşleşme başına en fazla iki etkin dosya işlemi olabilir.");

            using var picker = new OpenFileDialog
            {
                Title = "Telefona göndereceğiniz dosyayı seçin",
                CheckFileExists = true,
                Multiselect = false
            };
            if (picker.ShowDialog(this) != DialogResult.OK)
                return;
            TransferSecurity.ValidateTransferFile(picker.FileName, state.AllowedFolders);
            var metadata = await TransferSecurity.HashFileAsync(picker.FileName, lifetime.Token);
            if (metadata.Size == 0)
                throw new InvalidOperationException("Boş dosyalar aktarılamaz.");

            using var client = NewClient();
            var action = await client.CreateActionAsync(
                "pc_to_phone", Path.GetFileName(picker.FileName), metadata.Size, metadata.Sha256, lifetime.Token);
            action.source_path = Path.GetFullPath(picker.FileName);
            state.SourceFilesByAction[action.action_id] = action.source_path;
            SaveState();
            AddOrUpdateAction(action);
            Report($"“{action.filename}” için istek oluşturuldu. Android biyometrik onayı bekleniyor.");
        }
        catch (Exception error)
        {
            Report("Gönderim başlatılamadı: " + error.Message);
        }
    }

    private async Task ReceiveFileAsync()
    {
        try
        {
            EnsurePaired();
            if (queue.CurrentRow?.Tag is not TransferAction action)
                throw new InvalidOperationException("Kuyruktan bir telefon → bilgisayar işlemi seçin.");
            if (action.direction != "phone_to_pc" || action.state != "ready")
                throw new InvalidOperationException("Seçilen işlem Android tarafından gönderilmiş ve indirmeye hazır olmalıdır.");
            if (state.AllowedFolders.Count == 0)
                throw new InvalidOperationException("Önce en az bir izinli klasör ekleyin.");

            var safeName = TransferSecurity.ValidateRemoteFilename(action.filename);
            using var picker = new FolderBrowserDialog
            {
                Description = "Dosyayı kaydetmek için izinli klasörlerden birini seçin.",
                UseDescriptionForTitle = true,
                ShowNewFolderButton = false,
                SelectedPath = state.AllowedFolders[0]
            };
            if (picker.ShowDialog(this) != DialogResult.OK)
                return;
            var folder = Path.GetFullPath(picker.SelectedPath);
            if (!TransferSecurity.IsPathAllowed(folder, state.AllowedFolders))
                throw new UnauthorizedAccessException("Kaydetme klasörü izin verilen klasörlerden birinin içinde olmalıdır.");
            TransferSecurity.EnsureNoReparsePoints(folder);
            var destination = Path.Combine(folder, safeName);
            if (!TransferSecurity.IsPathAllowed(destination, state.AllowedFolders))
                throw new UnauthorizedAccessException("Hedef dosya izin verilen klasörlerin dışında.");
            if (File.Exists(destination))
                throw new IOException("Bu klasörde aynı adlı dosya zaten var; mevcut dosyanın üzerine yazılmadı.");

            using var client = NewClient();
            await client.DownloadReadyFileAsync(action, destination, lifetime.Token);
            action.state = "completed";
            Report($"“{action.filename}” güvenli biçimde indirildi: {destination}");
            await RefreshAsync(false);
        }
        catch (Exception error)
        {
            Report("Dosya alınamadı: " + error.Message);
        }
    }

    private async Task CancelSelectedAsync()
    {
        if (queue.CurrentRow?.Tag is not TransferAction action)
        {
            Report("İptal etmek için kuyruktan bir işlem seçin.");
            return;
        }
        if (MessageBox.Show(this, $"“{action.filename}” işlemi iptal edilsin mi?", "İşlemi iptal et",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        try
        {
            using var client = NewClient();
            await client.CancelActionAsync(action.action_id, lifetime.Token);
            Report($"“{action.filename}” işlemi iptal edildi.");
            await RefreshAsync(false);
        }
        catch (Exception error)
        {
            Report("İşlem iptal edilemedi: " + error.Message);
        }
    }

    private async Task ReattachApprovedSourceAsync()
    {
        try
        {
            EnsurePaired();
            if (queue.CurrentRow?.Tag is not TransferAction action
                || action.direction != "pc_to_phone"
                || action.state != "approved")
                throw new InvalidOperationException("Android tarafından onaylanmış bir bilgisayardan telefona işlemi seçin.");

            using var picker = new OpenFileDialog
            {
                Title = "Onaylanan dosyanın aynısını yeniden seçin",
                CheckFileExists = true,
                Multiselect = false,
                FileName = action.filename
            };
            if (picker.ShowDialog(this) != DialogResult.OK)
                return;
            TransferSecurity.ValidateTransferFile(picker.FileName, state.AllowedFolders);
            var metadata = await TransferSecurity.HashFileAsync(picker.FileName, lifetime.Token);
            if (!string.Equals(Path.GetFileName(picker.FileName), action.filename, StringComparison.Ordinal)
                || metadata.Size != action.size
                || !CryptographicOperations.FixedTimeEquals(
                    System.Text.Encoding.ASCII.GetBytes(metadata.Sha256),
                    System.Text.Encoding.ASCII.GetBytes(action.sha256)))
                throw new InvalidDataException("Seçilen dosya adı, boyutu veya SHA-256 değeri onaylanan işlemle eşleşmiyor.");

            action.source_path = Path.GetFullPath(picker.FileName);
            state.SourceFilesByAction[action.action_id] = action.source_path;
            uploadFailed.Remove(action.action_id);
            SaveState();
            Report($"Onaylanan dosya yeniden eklendi: {action.filename}");
        }
        catch (Exception error)
        {
            Report("Dosya yeniden seçilemedi: " + error.Message);
        }
    }

    private async Task RefreshAsync(bool reportErrors)
    {
        if (polling || IsDisposed || string.IsNullOrEmpty(state.PairingId) || string.IsNullOrEmpty(state.PcToken))
            return;
        polling = true;
        try
        {
            using var client = NewClient();
            var pairState = await client.GetPairingStateAsync(state.PairingId, state.PcToken, lifetime.Token);
            if (pairState == "waiting")
            {
                var codeExpired = state.PairingCodeExpiresAt > 0
                    && state.PairingCodeExpiresAt <= DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                if (codeExpired)
                {
                    state.PairingCode = "";
                    state.PairingCodeExpiresAt = 0;
                    stateStore.Save(state);
                    pairingCode.Text = "";
                }
                UpdatePairingDisplay(codeExpired
                    ? "Eşleştirme kodunun süresi doldu. Bu eşleşmeyi iptal edip yeni kod oluşturun."
                    : "Telefon bekleniyor. Android uygulamasında bu bilgisayarın kodunu onaylayın.");
                return;
            }
            if (pairState != "paired")
                throw new InvalidDataException("Sunucudan bilinmeyen eşleşme durumu alındı.");

            if (state.PairingCode.Length > 0)
            {
                state.PairingCode = "";
                state.PairingCodeExpiresAt = 0;
                stateStore.Save(state);
            }
            pairingCode.Text = "";
            UpdatePairingDisplay("Telefon eşleşti. Dosya işlemleri için her seferinde Android biyometrik onayı gerekir.");
            var serverActions = await client.GetActionsAsync(lifetime.Token);
            var receivedIds = serverActions.Select(action => action.action_id).ToHashSet(StringComparer.Ordinal);
            var stateChanged = false;
            foreach (var serverAction in serverActions)
            {
                if (state.SourceFilesByAction.TryGetValue(serverAction.action_id, out var source))
                    serverAction.source_path = source;
                if (serverAction.state is "completed" or "cancelled")
                {
                    serverAction.source_path = null;
                    stateChanged |= state.SourceFilesByAction.Remove(serverAction.action_id);
                }
                AddOrUpdateAction(serverAction);
            }
            foreach (var expired in actions.Where(action =>
                         !receivedIds.Contains(action.action_id)
                         && IsActive(action.state)
                         && action.expires_at > 0
                         && action.expires_at <= DateTimeOffset.UtcNow.ToUnixTimeSeconds()))
            {
                expired.state = "expired";
                expired.source_path = null;
                stateChanged |= state.SourceFilesByAction.Remove(expired.action_id);
            }
            if (stateChanged)
                SaveState();

            foreach (var action in actions.Where(item =>
                         item.direction == "pc_to_phone"
                         && item.state == "approved"
                         && item.source_path is not null
                         && !uploading.Contains(item.action_id)
                         && !uploadFailed.Contains(item.action_id)).ToArray())
            {
                uploading.Add(action.action_id);
                RefreshQueue();
                try
                {
                    TransferSecurity.ValidateTransferFile(action.source_path!, state.AllowedFolders);
                    var meta = await TransferSecurity.HashFileAsync(action.source_path!, lifetime.Token);
                    if (meta.Size != action.size || !CryptographicOperations.FixedTimeEquals(
                            System.Text.Encoding.ASCII.GetBytes(meta.Sha256),
                            System.Text.Encoding.ASCII.GetBytes(action.sha256)))
                        throw new InvalidDataException("Dosya, Android tarafından onaylanan boyut veya SHA-256 ile artık eşleşmiyor.");
                    await client.UploadApprovedFileAsync(action, lifetime.Token);
                    action.source_path = null;
                    action.state = "ready";
                    state.SourceFilesByAction.Remove(action.action_id);
                    SaveState();
                    Report($"“{action.filename}” Android onayından sonra sunucuya gönderildi.");
                }
                catch (Exception error) when (error is not OperationCanceledException)
                {
                    uploadFailed.Add(action.action_id);
                    action.source_path = null;
                    state.SourceFilesByAction.Remove(action.action_id);
                    SaveState();
                    Report($"“{action.filename}” gönderilemedi: {error.Message} Dosyayı yeniden seçip deneyin veya işi iptal edin.");
                }
                finally
                {
                    uploading.Remove(action.action_id);
                }
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            if (reportErrors)
                Report("Durum yenilenemedi: " + error.Message);
            else
                UpdatePairingDisplay("Bağlantı bekleniyor: " + error.Message);
        }
        finally
        {
            polling = false;
            RefreshQueue();
        }
    }

    private void AddOrUpdateAction(TransferAction action)
    {
        var existing = actions.FirstOrDefault(item => item.action_id == action.action_id);
        if (existing is null)
        {
            actions.Add(action);
            return;
        }

        existing.direction = action.direction;
        existing.filename = action.filename;
        existing.size = action.size;
        existing.sha256 = action.sha256;
        existing.state = action.state;
        existing.expires_at = action.expires_at;
        existing.source_path ??= action.source_path;
    }

    private void RefreshQueue()
    {
        var selectedId = queue.CurrentRow?.Tag is TransferAction selected ? selected.action_id : null;
        queue.Rows.Clear();
        foreach (var action in actions)
        {
            var row = new DataGridViewRow();
            row.CreateCells(queue,
                action.action_id,
                action.direction == "pc_to_phone" ? "Bilgisayar → telefon" : "Telefon → bilgisayar",
                action.filename,
                FormatSize(action.size),
                DisplayState(action));
            row.Tag = action;
            queue.Rows.Add(row);
            if (selectedId == action.action_id)
            {
                row.Selected = true;
                queue.CurrentCell = row.Cells[1];
            }
        }
    }

    private void RefreshFolders()
    {
        folders.Items.Clear();
        foreach (var folder in state.AllowedFolders)
            folders.Items.Add(folder);
    }

    private void SaveState()
    {
        state.ServerUrl = serverUrl.Text.Trim();
        stateStore.Save(state);
    }

    private void EnsurePaired()
    {
        if (string.IsNullOrEmpty(state.PairingId) || string.IsNullOrEmpty(state.PcToken))
            throw new InvalidOperationException("Önce bu bilgisayarı telefonla eşleştirin.");
        if (!string.Equals(TransferSecurity.ValidateServerUrl(serverUrl.Text), state.ServerUrl, StringComparison.Ordinal))
            throw new InvalidOperationException("Sunucu adresi mevcut eşleşmeyle aynı olmalıdır. Önce eşleşmeyi iptal edin.");
    }

    private TransferClient NewClient()
    {
        EnsurePaired();
        return new TransferClient(state.ServerUrl, state.PairingId, state.PcToken);
    }

    private void UpdatePairingDisplay(string? message = null)
    {
        var hasPairing = !string.IsNullOrEmpty(state.PairingId);
        pairButton.Text = hasPairing ? "Yeni eşleşme için önce iptal edin" : "Eşleştir";
        pairButton.Enabled = !hasPairing;
        serverUrl.Enabled = !hasPairing;
        if (message is not null)
            pairingStatus.Text = message;
        else if (!hasPairing)
            pairingStatus.Text = "Henüz eşleşme yok.";
        else if (state.PairingCode.Length > 0
                 && state.PairingCodeExpiresAt > DateTimeOffset.UtcNow.ToUnixTimeSeconds())
        {
            pairingCode.Text = state.PairingCode;
            pairingStatus.Text = "Telefon eşleştirme kodu";
        }
        else if (state.PairingCode.Length > 0)
        {
            pairingCode.Text = "";
            pairingStatus.Text = "Eşleştirme kodunun süresi dolmuş olabilir; sunucu durumu kontrol ediliyor.";
        }
        else if (state.PairingCode.Length == 0)
        {
            pairingStatus.Text = "Sunucu eşleşme durumu kontrol ediliyor.";
        }
    }

    private void ClearPairing(string message)
    {
        state.PairingId = "";
        state.PcToken = "";
        state.PairingCode = "";
        state.PairingCodeExpiresAt = 0;
        state.SourceFilesByAction.Clear();
        stateStore.Save(state);
        actions.Clear();
        queue.Rows.Clear();
        pairingCode.Text = "";
        UpdatePairingDisplay(message);
    }

    private void Report(string message)
    {
        activity.Text = $"{DateTime.Now:HH:mm:ss}  {message}";
    }

    private static bool IsActive(string value) =>
        value is "awaiting_approval" or "approved" or "receiving" or "ready" or "sending";

    private string DisplayState(TransferAction action)
    {
        if (uploading.Contains(action.action_id))
            return "Dosya bilgisayardan sunucuya yükleniyor";
        if (action.state == "approved" && action.direction == "pc_to_phone" && action.source_path is null)
            return "Android onayladı - dosyayı yeniden seçin";
        return action.state switch
        {
            "awaiting_approval" => "Android biyometrik onayı bekleniyor",
            "approved" => "Android onayladı",
            "receiving" => "Sunucu dosyayı alıyor",
            "ready" => "İndirmeye hazır",
            "sending" => "İndiriliyor",
            "completed" => "Tamamlandı",
            "cancelled" => "İptal edildi",
            "expired" => "Süresi doldu",
            _ => action.state
        };
    }

    private static string FormatSize(long size) => $"{size:N0} bayt";
}
