package cn.chendian.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.chendian.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ReminderLabels = listOf("不提醒", "到期当天", "提前一天", "两者都提醒")
private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun formatted(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateFormat)
private fun status(b: JSONObject, now: Long = System.currentTimeMillis()) = BanTime.state(b.optLong("start"), b.optLong("end"),
    b.optInt("permanent") == 1, b.optLong("confirmed"), now)
private fun JSONObject.title() = optString("name").ifBlank { optString("identifier") }
private fun JSONObject.accountLabel(ban: Boolean = false) = listOf(optString("type"), optString("identifier").ifBlank { optString(if (ban) "accountName" else "name") }).filter { it.isNotBlank() }.joinToString(" · ")
private val Platforms = listOf("全平台", "安卓", "苹果", "鸿蒙", "PC")
private fun JSONObject.location() = listOf(optString("platform"), optString("region")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "不分区" }
private fun JSONObject.roleLabel(idKey: String = "gameRoleId") = listOf(optString("nickname"), optString(idKey).takeIf { it.isNotBlank() }?.let { "ID $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private var notificationBan by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        notificationBan = intent.getStringExtra("banId")
        setContent { HandbookTheme { Handbook() } }
        Reminders.schedule(this, store)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationBan = intent.getStringExtra("banId")
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) Reminders.schedule(this, store)
    }

    override fun onDestroy() {
        super.onDestroy()
        store.close()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun openExternalLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { toast("未找到可打开链接的应用，请安装浏览器后重试") }
    }

    @Composable
    private fun Handbook() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var detail by rememberSaveable { mutableStateOf<String?>(null) }
        var role by rememberSaveable { mutableStateOf<String?>(null) }
        var account by rememberSaveable { mutableStateOf<String?>(null) }
        var game by rememberSaveable { mutableStateOf(store.one("SELECT value FROM settings WHERE id=?", "overviewGame")?.optString("value") ?: "全部游戏") }
        var search by rememberSaveable { mutableStateOf("") }
        var byAccount by rememberSaveable { mutableStateOf(store.one("SELECT value FROM settings WHERE id=?", "overviewMode")?.optString("value") == "account") }
        var overviewAccount by rememberSaveable { mutableStateOf(store.one("SELECT value FROM settings WHERE id=?", "overviewAccount")?.optString("value") ?: "") }
        var revision by remember { mutableIntStateOf(0) }
        var clockTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var editor by remember { mutableStateOf<Editor?>(null) }
        var confirmation by remember { mutableStateOf<Confirmation?>(null) }
        var managingGames by remember { mutableStateOf(false) }
        var libraryOpen by remember { mutableStateOf(false) }
        var addingGame by remember { mutableStateOf(false) }
        var restoreOpen by remember { mutableStateOf(false) }
        var importOpen by rememberSaveable { mutableStateOf(false) }
        var replace by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf<String?>(null) }
        var preview by remember { mutableStateOf<File?>(null) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) { if (tab !in 0..2) tab = 2 }
        LaunchedEffect(tab, detail, role) { listState.scrollToItem(0) }

        fun refresh() {
            Backup.prune(this, store); Reminders.schedule(this, store)
            if (detail != null && store.ban(detail) == null) detail = null
            if (role != null && store.one("SELECT id FROM roles WHERE id=?", role) == null) role = null
            revision++
        }
        fun execute(label: String, work: suspend () -> String, done: () -> Unit = {}) {
            if (busy != null) return
            busy = label
            scope.launch {
                try { val message = work(); refresh(); done(); if (message.isNotEmpty()) toast(message) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast("操作失败：${e.message}") }
                finally { busy = null }
            }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it) toast("未获得通知权限，可在系统设置中开启")
            revision++
        }
        fun notifications() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) execute("正在导出完整备份…", {
                withContext(Dispatchers.IO) { Store(applicationContext).use { workerStore ->
                    contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out) { "无法打开目标文件" }; Backup.write(this@MainActivity, workerStore, out)
                    }
                } }; "备份已导出"
            })
        }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { val replacing = replace
                execute("正在校验并恢复备份…", {
                    val count = withContext(Dispatchers.IO) {
                        Store(applicationContext).use { workerStore ->
                            Reminders.cancelAll(this@MainActivity, workerStore)
                            try { contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "无法打开备份" }; Backup.restore(this@MainActivity, workerStore, input, replacing)
                            } } finally { Reminders.schedule(this@MainActivity, workerStore) }
                        }
                    }; "恢复完成，新增 $count 条封禁记录"
                }, {
                    detail = null; role = null; account = null
                    game = store.one("SELECT value FROM settings WHERE id=?", "overviewGame")?.optString("value") ?: "全部游戏"
                    byAccount = store.one("SELECT value FROM settings WHERE id=?", "overviewMode")?.optString("value") == "account"
                    overviewAccount = store.one("SELECT value FROM settings WHERE id=?", "overviewAccount")?.optString("value") ?: ""
                })
            }
        }
        val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) execute("正在加载图片…", {
                val file = withContext(Dispatchers.IO) { copyImage(uri) }; preview?.delete(); preview = file; ""
            })
        }
        LaunchedEffect(notificationBan) {
            notificationBan?.let { if (store.ban(it) != null) { detail = it; role = null } else toast("这条记录已删除") }
            notificationBan = null
        }
        LaunchedEffect(Unit) { while (true) { delay(1_000); clockTime = System.currentTimeMillis() } }
        val latestPreview by rememberUpdatedState(preview)
        DisposableEffect(Unit) { onDispose { latestPreview?.delete() } }
        BackHandler(detail != null || role != null || tab != 0 || busy != null) {
            if (busy == null) when { detail != null -> detail = null; role != null -> role = null; else -> tab = 0 }
        }
        val bans = remember(revision) { store.bans().toList() }
        val current = remember(revision) { store.currentIds() }
        val games = remember(revision) { store.query("SELECT * FROM games ORDER BY name") }
        val accounts = remember(revision) { store.query("SELECT * FROM accounts ORDER BY type,CASE WHEN identifier='' THEN name ELSE identifier END") }
        val avatars = remember(revision) { store.query("SELECT * FROM avatars") }
        val accountAvatars = avatars.filter { it.has("accountId") }.associate { it.optString("accountId") to it.optString("file") }
        val gameAvatars = games.associate { g -> g.optString("name") to avatars.firstOrNull { it.optString("gameId") == g.optString("id") }?.optString("file").orEmpty() }
        LaunchedEffect(games, game) { if (game != "全部游戏" && games.none { it.optString("name") == game }) { game = "全部游戏"; store.setting("overviewGame", game) } }
        LaunchedEffect(accounts, overviewAccount) {
            if (overviewAccount.isNotEmpty() && accounts.none { it.optString("id") == overviewAccount }) {
                overviewAccount = ""; store.setting("overviewAccount", "")
            }
        }

        Scaffold(containerColor = Background, topBar = {
            if (tab == 0 && detail == null && role == null) {
                Surface(color = Background, modifier = Modifier.statusBarsPadding()) {
                    OutlinedTextField(value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        singleLine = true, placeholder = { Text("搜索账号 / 角色 / 备注") }, shape = MaterialTheme.shapes.large)
                }
            }
        }, bottomBar = {
            Column {
                if (tab == 1 && detail == null && role == null) {
                    Surface(color = Background) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            FilledTonalButton(onClick = { importOpen = true }, enabled = busy == null) { Text("↓ 导入") }
                        }
                    }
                }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("总览", "账号", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, enabled = busy == null,
                        onClick = { tab = index; detail = null; role = null },
                        icon = { Text(listOf("◈", "◎", "⚙")[index], fontSize = 23.sp) }, label = { Text(title) })
                }
            }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val selectedBan = detail?.let(store::ban)
                val selectedRole = role?.let { store.one("SELECT r.*,g.name AS game,z.name AS region,z.platform FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.id=?", it) }
                when {
                    selectedBan != null -> {
                        val b = selectedBan; val id = b.optString("id")
                        item { TextButton(onClick = { detail = null }) { Text("‹ 返回") }; Heading(b.optString("game"), "${b.location()} · ${b.roleLabel()}") }
                        item { BanCard(b, current.contains(id), clockTime) {} }
                        item { GoldPanel {
                            val values = listOf("账号" to b.accountLabel(true),
                                "昵称" to b.optString("nickname"), "封禁开始" to formatted(b.optLong("start")),
                                "预计解封" to if (b.optInt("permanent") == 1) "永久" else formatted(b.optLong("end")),
                                "封禁时长" to if (b.optInt("permanent") == 1) "永久" else durationText(b.optLong("end") - b.optLong("start")),
                                "实际确认解封" to if (b.optLong("confirmed") > 0) formatted(b.optLong("confirmed")) else "尚未确认",
                                "提醒" to ReminderLabels[b.optInt("remind").coerceIn(0, 3)], "原因" to b.optString("reason"), "备注" to b.optString("note"))
                            values.forEach { (label, value) -> Text(label, color = Gold, style = MaterialTheme.typography.labelMedium); androidx.compose.foundation.text.selection.SelectionContainer { Text(value.ifBlank { "未填写" }) } }
                        } }
                        items(store.query("SELECT * FROM attachments WHERE banId=?", id), key = { "attachment:${it.optString("id")}" }) { Screenshot(File(Backup.directory(this@MainActivity), it.optString("file"))) }
                        item { Action(if (b.optLong("confirmed") == 0L) "确认实际已解封" else "撤销解封确认") {
                            confirmation = Confirmation("确认解封状态？", "请先在游戏中核实，提醒将随确认状态更新。") {
                                store.writableDatabase.execSQL("UPDATE bans SET confirmed=? WHERE id=?", arrayOf(if (b.optLong("confirmed") == 0L) System.currentTimeMillis() else 0L, id)); refresh()
                            }
                        }; Action("编辑封禁记录") { editor = Editor.Ban(b.optString("roleId"), b) }
                            Action("记录该角色新的封禁") { editor = Editor.Ban(b.optString("roleId")) }
                            Action("删除本次封禁记录") { confirmation = Confirmation("删除封禁记录？", "本次记录和附件将被删除，无法撤销。") { Reminders.cancel(this@MainActivity, id); store.writableDatabase.delete("bans", "id=?", arrayOf(id)); detail = null; refresh() } }
                        }
                    }
                    selectedRole != null -> {
                        val r = selectedRole
                        item { TextButton(onClick = { role = null }) { Text("‹ 返回账号") }; Heading(r.optString("nickname").ifBlank { r.optString("roleId") }, "${r.optString("game")} · ${r.location()}" + if (r.optString("roleId").isNotBlank()) " · ID ${r.optString("roleId")}" else "") }
                        item { Action("＋ 新增一次封禁") { editor = Editor.Ban(r.optString("id")) }; Action("编辑角色") { editor = Editor.Role(r.optString("accountId"), r) } }
                        val records = bans.filter { it.optString("roleId") == r.optString("id") }
                        if (records.isEmpty()) item { Empty("这个角色还没有封禁记录") }
                        items(records, key = { it.optString("id") }) { BanCard(it, current.contains(it.optString("id")), clockTime) { detail = it.optString("id") } }
                    }
                    tab == 0 -> {
                        // Each mode scopes independently; a previous game selection must not hide an account's other games.
                        val active = bans.filter { b -> current.contains(b.optString("id")) &&
                            if (byAccount) overviewAccount.isEmpty() || overviewAccount == b.optString("accountId")
                            else game == "全部游戏" || game == b.optString("game") }
                        item { GoldPanel { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val now = clockTime
                            listOf("封禁中角色" to active.count { status(it) == BanTime.ACTIVE }, "今日预计解封" to active.count { it.optInt("permanent") == 0 && it.optLong("confirmed") == 0L && BanTime.today(it.optLong("end"), now, ZoneId.systemDefault()) }, "永久封禁" to active.count { status(it) == BanTime.PERMANENT }).forEach { (label, count) -> Column { Text("$count", fontSize = 28.sp, color = Gold); Text(label, fontSize = 11.sp, color = Muted) } }
                        } } }
                        item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("按游戏", "按账号").forEachIndexed { index, label ->
                                SegmentedButton(selected = byAccount == (index == 1),
                                    onClick = { byAccount = index == 1; store.setting("overviewMode", if (byAccount) "account" else "game") },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                            }
                        } }
                        item { key(byAccount) { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (byAccount) {
                                FilterChip(selected = overviewAccount.isEmpty(), onClick = { overviewAccount = ""; store.setting("overviewAccount", "") }, label = { Text("全部账号") })
                                accounts.forEach { a ->
                                    val id = a.optString("id")
                                    FilterChip(selected = overviewAccount == id,
                                        onClick = { overviewAccount = id; store.setting("overviewAccount", id) },
                                        leadingIcon = { Avatar(accountAvatars[id].orEmpty(), a.title(), 24.dp) },
                                        label = { Text("${a.title()} · ${a.optString("type")}" + if (a.optString("name").isNotBlank() && a.optString("identifier").isNotBlank()) " · ${a.optString("identifier")}" else "") })
                                }
                            } else {
                            (listOf("全部游戏") + games.map { it.optString("name") }).forEach { name -> FilterChip(selected = game == name, onClick = { game = name; store.setting("overviewGame", name) }, leadingIcon = if (name == "全部游戏") null else { { Avatar(gameAvatars[name].orEmpty(), name, 24.dp) } }, label = { Text(name) }) }
                            AssistChip(onClick = { addingGame = true }, label = { Text("＋ 游戏") })
                            }
                        } } }
                        val needle = search.trim().lowercase(Locale.ROOT)
                        val records = active.filter { b ->
                            status(b, clockTime) != BanTime.CONFIRMED &&
                                listOf("identifier", "accountName", "game", "platform", "region", "gameRoleId", "nickname", "note", "reason").joinToString(" ") { b.optString(it) }.lowercase(Locale.ROOT).contains(needle)
                        }.sortedWith(compareBy<JSONObject> { if (status(it) == BanTime.ACTIVE) it.optLong("end") else Long.MAX_VALUE }.thenByDescending { it.optLong("start") })
                        val groups = records.groupBy { it.optString("accountId") to it.optString("regionId") }.values.toList()
                        if (groups.isEmpty()) item { Empty(if (search.isBlank()) "暂无封禁账号" else "没有匹配的账号") }
                        items(groups, key = { rows -> rows.first().let { b -> "overview:${b.optString("accountId")}:${b.optString("regionId")}" } }) { rows ->
                            var expanded by remember(byAccount, game, overviewAccount) { mutableStateOf(false) }
                            val lead = rows.firstOrNull { status(it) == BanTime.ACTIVE } ?: rows.first()
                            GoldPanel(Modifier.animateContentSize().clickable { if (rows.size == 1) detail = lead.optString("id") else expanded = !expanded }) {
                                Text((if (byAccount) lead.optString("accountName").ifBlank { lead.optString("identifier") } else lead.optString("game")) + " · " + status(lead), color = Muted, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val title = if (byAccount) lead.optString("game") else lead.optString("accountName").ifBlank { lead.optString("identifier") }
                                    Avatar(if (byAccount) gameAvatars[lead.optString("game")].orEmpty() else accountAvatars[lead.optString("accountId")].orEmpty(), title)
                                    Text(title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Gold, modifier = Modifier.weight(1f))
                                }
                                Text(lead.accountLabel(true), color = Muted)
                                rows.distinctBy { it.optString("roleId") }.take(2).forEach { Text("${it.location()} · ${it.roleLabel()}") }
                                val roleCount = rows.distinctBy { it.optString("roleId") }.size
                                if (roleCount > 2) Text("另有 ${roleCount - 2} 个角色", color = Muted, fontSize = 12.sp)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Text(remaining(lead, clockTime), fontSize = 23.sp, color = Gold)
                                Text(if (lead.optInt("permanent") == 1) "无预计解封时间" else "${formatted(lead.optLong("end"))} 预计解封", color = Muted, fontSize = 12.sp)
                                AnimatedVisibility(expanded) {
                                    Column {
                                        rows.forEach { b ->
                                            TextButton(onClick = { detail = b.optString("id") }) {
                                                Text("${b.location()} · ${b.roleLabel()} · ${status(b)}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tab == 1 -> {
                        item { Heading("账号"); Action("＋ 添加 QQ / 微信账号") { editor = Editor.Account() }; Action("管理游戏、平台与大区") { managingGames = true } }
                        if (accounts.isEmpty()) item { Empty("暂无账号") }
                        items(accounts, key = { it.optString("id") }) { a ->
                            val id = a.optString("id")
                            GoldPanel(Modifier.animateContentSize()) {
                                Row(Modifier.fillMaxWidth().clickable { account = if (account == id) null else id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Avatar(accountAvatars[id].orEmpty(), a.title())
                                    Column(Modifier.weight(1f)) { Text(a.title(), fontSize = 25.sp, color = Gold); Text("${a.accountLabel()}  ${if (account == id) "▾" else "▸"}", color = Muted) }
                                }
                                AnimatedVisibility(account == id) { Column {
                                    Action("编辑账号") { editor = Editor.Account(a) }; Action("＋ 添加游戏角色") { editor = Editor.Role(id) }
                                    store.query("SELECT r.*,z.name AS region,z.platform,g.name AS game FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.accountId=? ORDER BY g.name,z.platform,z.name,r.nickname,r.roleId", id).forEach { r ->
                                        TextButton(onClick = { role = r.optString("id") }) { Text("${r.optString("game")} / ${r.location()}\n${r.roleLabel("roleId")}") }
                                    }
                                } }
                            }
                        }
                    }
                    else -> {
                        item { Heading("设置") }
                        item { GoldPanel { Text("默认提醒", fontSize = 22.sp, color = Gold); Choice("提醒规则", ReminderLabels, ReminderLabels[store.defaultReminder().coerceIn(0, 3)]) { store.setting("defaultRemind", ReminderLabels.indexOf(it).toString()); refresh() }; Action("通知权限与管理") { notifications() } } }
                        item { GoldPanel { Text("备份与恢复", fontSize = 22.sp, color = Gold); Text("支持恢复账号备份，也可导入游戏名称与图标库。", color = Muted, style = MaterialTheme.typography.bodySmall); Action("导出备份") { export.launch("封禁手册_${LocalDate.now()}.zip") }; Action("恢复备份 / 导入游戏库") { restoreOpen = true } } }
                        item { GoldPanel {
                            Text("关于与反馈", fontSize = 22.sp, color = Gold)
                            listOf(
                                "GitHub 仓库" to "https://github.com/yunhe4550-design/Termination-Handbook",
                                "Telegram 反馈" to "https://t.me/mbazhuan"
                            ).forEach { (label, url) ->
                                TextButton(onClick = { openExternalLink(url) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("$label ↗", color = Gold)
                                        Text(url.removePrefix("https://"), color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        } }
                        item { Text("封禁手册 ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (importOpen) EditorDialog("导入", { importOpen = false }) {
            GoldPanel { Text("网页导入", fontSize = 22.sp, color = Gold); Text("识别待完善", color = Muted); Action("打开浏览器") { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) } }
            GoldPanel { Text("图片导入", fontSize = 22.sp, color = Gold); Text("识别待完善", color = Muted); Action("选择图片") { image.launch(arrayOf("image/*")) }; preview?.let { Screenshot(it) } }
            GoldPanel { Text("备份恢复 / 游戏库导入", fontSize = 22.sp, color = Gold); Text("恢复账号备份，或导入游戏名称与图标库。", color = Muted); Action("选择备份 / 游戏库") { importOpen = false; restoreOpen = true } }
        }
        if (managingGames) EditorDialog("游戏管理", { managingGames = false }) {
            var expandedGame by rememberSaveable { mutableStateOf<String?>(null) }
            FilledTonalButton(onClick = { addingGame = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加游戏") }
            Text("展开游戏管理平台与大区；全平台互通的游戏无需填写大区。", color = Muted, style = MaterialTheme.typography.bodySmall)
            games.forEach { g ->
                val gid = g.optString("id")
                key(gid) { GoldPanel {
                    var menuOpen by remember { mutableStateOf(false) }
                    val regions = store.query("SELECT *,name AS region FROM regions WHERE gameId=? ORDER BY platform,name", gid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(gameAvatars[g.optString("name")].orEmpty(), g.optString("name"), 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { expandedGame = if (expandedGame == gid) null else gid }.padding(vertical = 8.dp)) {
                            Text(g.optString("name") + if (expandedGame == gid) " ▾" else " ▸", color = Gold, fontSize = 20.sp)
                            Text("${regions.size} 项平台 / 大区", color = Muted, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("更多") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("编辑游戏与头像") }, onClick = { menuOpen = false; editor = Editor.Game(g) })
                                DropdownMenuItem(text = { Text("删除游戏", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; confirmation = Confirmation("删除游戏？", "该游戏的角色及全部封禁历史将被删除，无法撤销。") { Reminders.cancelAll(this@MainActivity, store); store.writableDatabase.delete("games", "id=?", arrayOf(gid)); refresh() } })
                            }
                        }
                    }
                    AnimatedVisibility(expandedGame == gid) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        if (regions.isEmpty()) Text("尚未添加平台或大区，也可在添加角色时创建。", color = Muted, fontSize = 12.sp)
                        regions.forEach { z -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(z.location(), Modifier.weight(1f))
                            TextButton(onClick = { editor = Editor.Region(gid, z) }) { Text("编辑") }
                        } }
                        OutlinedButton(onClick = { editor = Editor.Region(gid) }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加平台 / 大区") }
                    } }
                } }
            }
        }
        if (addingGame) EditorDialog("选择添加游戏方式", { addingGame = false }) {
            Action("从游戏库选择 / 管理游戏库") { addingGame = false; libraryOpen = true }
            Text("选择已收集的游戏，自动添加名称与图标；也可在库中导入、收集和导出。", color = Muted)
            Action("手动填写游戏名称与图标") { addingGame = false; editor = Editor.Game() }
        }
        if (libraryOpen) LibraryDialog({ libraryOpen = false }, { refresh() })
        if (restoreOpen) EditorDialog("选择导入 / 恢复方式", { restoreOpen = false }) {
            Action("导入游戏库：添加游戏名称与图标") { restoreOpen = false; libraryOpen = true }
            Text("游戏库仅包含名称与图标；账号和封禁记录请使用下方备份恢复。", color = Muted)
            Action("合并：保留本地记录，补充备份数据") { replace = false; restoreOpen = false; restore.launch(arrayOf("*/*")) }
            Action("替换：清空本地档案后恢复备份") { restoreOpen = false; confirmation = Confirmation("替换全部本地数据？", "当前账号、角色、封禁记录、附件和设置将被替换。建议先导出备份。文件校验通过后执行。") { replace = true; restore.launch(arrayOf("*/*")) } }
        }
        editor?.let { e -> key(e) { Edit(e, store, { editor = null }, { savedBan ->
            editor = null; if (savedBan != null) detail = savedBan; refresh()
        }, { confirmation = it }, { notifications() }) } }
        confirmation?.let { c -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(c.title) }, text = { Text(c.message) }, confirmButton = { TextButton(onClick = { confirmation = null; try { c.action() } catch (e: Exception) { toast("操作失败：${e.message}") } }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
        if (busy != null) AlertDialog(onDismissRequest = {}, title = { Text(busy!!) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    }

    private fun copyImage(uri: Uri): File {
        require(contentResolver.getType(uri)?.startsWith("image/") == true) { "请选择图片文件" }
        val file = File(cacheDir, Store.uuid() + ".img")
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开图片" }
                file.outputStream().use { out -> val bytes = ByteArray(16384); var size = 0L
                    while (true) { val n = input.read(bytes); if (n == -1) break; size += n; require(size <= 32L * 1024 * 1024) { "单张图片不能超过 32 MB" }; out.write(bytes, 0, n) }
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "不支持此图片格式" }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun saveAvatar(accountId: String?, gameId: String?, draft: AvatarDraft) {
        val ownerKey = if (accountId != null) "accountId" else "gameId"
        val owner = accountId ?: requireNotNull(gameId)
        if (draft.removed) store.writableDatabase.delete("avatars", "$ownerKey=?", arrayOf(owner))
        val source = draft.file ?: return
        val dest = File(Backup.directory(this), Store.uuid() + ".img")
        try {
            source.copyTo(dest)
            val existing = store.one("SELECT id FROM avatars WHERE $ownerKey=?", owner)
            store.writableDatabase.execSQL("INSERT OR REPLACE INTO avatars(id,accountId,gameId,file) VALUES(?,?,?,?)",
                arrayOf(existing?.optString("id") ?: Store.uuid(), accountId, gameId, dest.name))
        } catch (e: Exception) { dest.delete(); throw e }
    }

    @Composable
    private fun AvatarPicker(current: String, draft: AvatarDraft, label: String) {
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                draft.loading = true; draft.error = ""
                scope.launch {
                    var copied: File? = null
                    try {
                        withContext(Dispatchers.IO) { copied = copyImage(uri) }
                        draft.file?.delete(); draft.file = copied; copied = null; draft.removed = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { draft.error = e.message ?: "头像加载失败" }
                    finally { copied?.delete(); draft.loading = false }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(draft.file?.absolutePath ?: if (draft.removed) "" else current, label, 64.dp)
            Column {
                TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !draft.loading) {
                    Text(if (draft.loading) "正在加载…" else if (draft.file != null || current.isNotEmpty() && !draft.removed) "更换头像" else "选择头像")
                }
                if (draft.file != null || current.isNotEmpty() && !draft.removed) TextButton(onClick = {
                    draft.file?.delete(); draft.file = null; draft.removed = true; draft.error = ""
                }, enabled = !draft.loading) { Text("移除头像", color = Muted) }
            }
        }
        Error(draft.error)
    }

    @Composable
    private fun LibraryDialog(dismiss: () -> Unit, changed: () -> Unit) {
        val library = remember { GameLibrary(this) }
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var adding by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun work(action: suspend () -> String) {
            if (working) return
            working = true; error = ""
            scope.launch {
                try {
                    val message = withContext(Dispatchers.IO) { action() }
                    rows = withContext(Dispatchers.IO) { library.read() }
                    changed()
                    if (message.isNotEmpty()) toast(message)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "游戏库操作失败" }
                finally { working = false }
            }
        }
        LaunchedEffect(Unit) { work { "" } }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) work {
                val count = contentResolver.openInputStream(uri).use { library.merge(requireNotNull(it)) }
                "导入完成，新增或补充 $count 个游戏"
            }
        }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) work {
                contentResolver.openOutputStream(uri, "wt").use { library.export(requireNotNull(it)) }
                "游戏库已导出，可将此文件分享给别人"
            }
        }
        EditorDialog("游戏名称与图标库", { if (!working) dismiss() }) {
            Text("独立保存名称与图标，导出文件可供其他人导入。重复名称合并，已有图标保留；不包含账号与封禁记录。", color = Muted)
            Row {
                TextButton(enabled = !working, onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入库") }
                TextButton(enabled = !working && rows.isNotEmpty(), onClick = { exporter.launch("沉淀一下-游戏库.json") }) { Text("导出分享") }
                TextButton(enabled = !working, onClick = { adding = true }) { Text("新增") }
            }
            Action("将游戏管理中的名称和图标收集到库", !working) { work { "已收集或补充 ${library.collect(store)} 个游戏" } }
            Field("搜索游戏（共 ${rows.size} 个）", query) { query = it }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Error(error)
            if (rows.isEmpty() && !working) Text("游戏库为空，请导入库文件或新增游戏。", color = Muted)
            if (rows.size > 50) Text("每次显示前 50 个匹配游戏，可搜索名称缩小范围。", color = Muted)
            rows.filter { it.getString("name").contains(query.trim(), true) }.take(50).forEach { row ->
                val name = row.getString("name")
                GoldPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(row) {
                            val bytes = android.util.Base64.decode(row.getString("icon"), android.util.Base64.DEFAULT)
                            if (bytes.isEmpty()) null else {
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                var sample = 1
                                while (options.outWidth / sample > 96 || options.outHeight / sample > 96) sample *= 2
                                options.inJustDecodeBounds = false; options.inSampleSize = sample
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            }
                        }
                        DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                        if (bitmap != null) Image(bitmap.asImageBitmap(), name, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Text(name, Modifier.weight(1f))
                        TextButton(enabled = !working, onClick = { work { library.select(row, store); "已添加到游戏管理：$name" } }) { Text("选择") }
                        TextButton(enabled = !working, onClick = { work { library.remove(name); "已从库移除，游戏管理中的记录保留" } }) { Text("移除") }
                    }
                }
            }
        }
        if (adding) {
            var name by remember { mutableStateOf("") }
            val draft = remember { AvatarDraft() }
            DisposableEffect(Unit) { onDispose { draft.file?.delete() } }
            EditorDialog("收集游戏", { if (!working) adding = false }) {
                Field("游戏名称", name) { name = it }
                AvatarPicker("", draft, name)
                Error(error)
                Text("同名条目会补充缺失的图标；更换已有图标时，请先从库移除再收集。", color = Muted)
                Button(enabled = !working && !draft.loading && name.isNotBlank(), onClick = {
                    work { library.add(name, draft.file); "已保存到游戏库" }
                }) { Text("保存到库") }
            }
        }
    }

    @Composable
    private fun Edit(e: Editor, store: Store, dismiss: () -> Unit, saved: (String?) -> Unit,
                     confirm: (Confirmation) -> Unit, notify: () -> Unit) {
        var error by remember { mutableStateOf("") }
        val avatar = remember { AvatarDraft() }
        DisposableEffect(Unit) { onDispose { avatar.file?.delete() } }
        fun save(action: () -> String?) { try { saved(action()) } catch (x: Exception) { error = x.message ?: "保存失败" } }
        when (e) {
            is Editor.Game -> {
                var name by remember { mutableStateOf(e.old?.optString("name") ?: "") }
                val currentAvatar = remember { e.old?.let { store.one("SELECT file FROM avatars WHERE gameId=?", it.optString("id"))?.optString("file") }.orEmpty() }
                EditorDialog(if (e.old == null) "添加游戏" else "编辑游戏", dismiss) {
                    AvatarPicker(currentAvatar, avatar, name)
                    Field("游戏名称（必填）", name) { name = it }
                    Error(error)
                    FilledTonalButton(onClick = { save {
                        required(name, "游戏名称")
                        val exists = store.one("SELECT id FROM games WHERE name=?", name.trim())
                        require(exists == null || exists.optString("id") == e.old?.optString("id")) { "该游戏已存在" }
                        val db = store.writableDatabase; db.beginTransaction()
                        try {
                            val id = e.old?.optString("id") ?: store.ensureGame(name)
                            if (e.old != null) db.execSQL("UPDATE games SET name=? WHERE id=?", arrayOf(name.trim(), id))
                            saveAvatar(null, id, avatar); db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                        null
                    } }, enabled = !avatar.loading, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                }
            }
            is Editor.Account -> {
                var type by remember { mutableStateOf(e.old?.optString("type") ?: "QQ") }
                var identifier by remember { mutableStateOf(e.old?.optString("identifier") ?: "") }
                var name by remember { mutableStateOf(e.old?.optString("name") ?: "") }
                val currentAvatar = remember { e.old?.let { store.one("SELECT file FROM avatars WHERE accountId=?", it.optString("id"))?.optString("file") }.orEmpty() }
                EditorDialog(if (e.old == null) "添加账号" else "编辑账号", dismiss) {
                    AvatarPicker(currentAvatar, avatar, name.ifBlank { identifier })
                    Choice("登录类型", listOf("QQ", "微信"), type) { type = it; error = "" }
                    Field(if (type == "QQ") "QQ 号" else "微信号", identifier) { identifier = it; error = "" }
                    Field(if (type == "QQ") "QQ 昵称" else "微信昵称", name) { name = it; error = "" }
                    Text("号码和昵称至少填写一项，也可以都填。", color = Muted, fontSize = 12.sp)
                    Error(error); Action("保存", !avatar.loading) { save {
                        require(identifier.isNotBlank() || name.isNotBlank()) { if (type == "QQ") "请至少填写 QQ 号或 QQ 昵称" else "请至少填写微信号或微信昵称" }
                        requiredLength(identifier); requiredLength(name)
                        val exists = store.findAccount(type, identifier, name)
                        require(exists == null || exists.optString("id") == e.old?.optString("id")) { "该账号已存在" }
                        val db = store.writableDatabase; db.beginTransaction()
                        try {
                            val id = e.old?.optString("id") ?: store.ensureAccount(type, identifier, name)
                            if (e.old != null) db.execSQL("UPDATE accounts SET type=?,identifier=?,name=? WHERE id=?", arrayOf(type, identifier.trim(), name.trim(), id))
                            saveAvatar(id, null, avatar); db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                        null
                    } }
                    if (e.old != null) Action("删除账号及全部角色和封禁记录") { confirm(Confirmation("删除账号？", "该账号的角色和封禁历史将全部删除，无法撤销。") { Reminders.cancelAll(this@MainActivity, store); store.writableDatabase.delete("accounts", "id=?", arrayOf(e.old.optString("id"))); saved(null) }) }
                }
            }
            is Editor.Region -> {
                var platform by remember { mutableStateOf(e.old?.optString("platform") ?: "全平台") }
                var region by remember { mutableStateOf(e.old?.optString("name") ?: "") }
                EditorDialog(if (e.old == null) "添加平台 / 大区" else "编辑平台 / 大区", dismiss) {
                    PlatformField(platform) { platform = it }
                    Field("大区名称（选填）", region) { region = it }
                    Text("没有大区时留空。同一个大区名称可以分别属于安卓、苹果等不同平台。", color = Muted, fontSize = 12.sp)
                    Error(error)
                    FilledTonalButton(onClick = { save {
                        requiredLength(platform); requiredLength(region)
                        require(platform.isNotBlank() || (e.old != null && e.old.optString("platform").isBlank() && region.isNotBlank())) { "请选择平台或填写平台名称" }
                        val exists = store.one("SELECT id FROM regions WHERE gameId=? AND platform=? AND name=?", e.game, platform.trim(), region.trim())
                        require(exists == null || exists.optString("id") == e.old?.optString("id")) { "该平台和大区已存在" }
                        if (e.old == null) store.ensureRegion(e.game, platform, region)
                        else store.writableDatabase.execSQL("UPDATE regions SET platform=?,name=? WHERE id=?", arrayOf(platform.trim(), region.trim(), e.old.optString("id")))
                        null
                    } }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                    if (e.old != null) {
                        HorizontalDivider()
                        TextButton(onClick = { confirm(Confirmation("删除平台 / 大区？", "其中的角色、封禁历史和附件将被删除，无法撤销。") {
                            Reminders.cancelAll(this@MainActivity, store); store.writableDatabase.delete("regions", "id=?", arrayOf(e.old.optString("id"))); saved(null)
                        }) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除此平台 / 大区及关联角色") }
                    }
                }
            }
            is Editor.Role -> {
                var game by remember { mutableStateOf(e.old?.optString("game") ?: "") }
                var region by remember { mutableStateOf(e.old?.optString("region") ?: "") }
                var platform by remember { mutableStateOf(e.old?.optString("platform") ?: "全平台") }
                var id by remember { mutableStateOf(e.old?.optString("roleId") ?: "") }
                var nick by remember { mutableStateOf(e.old?.optString("nickname") ?: "") }
                EditorDialog(if (e.old == null) "添加游戏角色" else "编辑角色", dismiss) {
                    Field("游戏名称（必填，可自行输入）", game) { game = it; region = ""; platform = "全平台" }
                    Choice("已有游戏", store.query("SELECT name FROM games ORDER BY name").map { it.optString("name") }, game) { game = it; region = ""; platform = "全平台" }
                    PlatformField(platform) { platform = it; region = "" }
                    Text("互通账号选全平台；平台独立的账号分别选择安卓、苹果等。", color = Muted, fontSize = 12.sp)
                    Field("游戏大区（选填，无大区可留空）", region) { region = it }
                    val existingRegions = store.query("SELECT z.name FROM regions z JOIN games g ON g.id=z.gameId WHERE g.name=? AND z.platform=? AND z.name<>'' ORDER BY z.name", game.trim(), platform.trim()).map { it.optString("name") }
                    if (existingRegions.isNotEmpty()) Choice("已有大区", existingRegions, region) { region = it }
                    Field("游戏昵称（必填）", nick) { nick = it }
                    Field("角色 ID（选填）", id) { id = it }
                    Error(error)
                    FilledTonalButton(onClick = { save {
                        required(game, "游戏名称"); required(nick, "游戏昵称"); requiredLength(id); requiredLength(platform); requiredLength(region)
                        require(platform.isNotBlank() || (e.old != null && e.old.optString("platform").isBlank() && region.isNotBlank())) { "请选择平台或填写平台名称" }
                        val db = store.writableDatabase; db.beginTransaction()
                        try { val gid = store.ensureGame(game); val zid = store.ensureRegion(gid, platform, region)
                            val duplicate = store.findRole(e.account, zid, id, nick)
                            require(duplicate == null || duplicate.optString("id") == e.old?.optString("id")) { if (id.isBlank()) "该账号、平台和大区下已存在同昵称且未填 ID 的角色" else "该账号、平台和大区下的角色 ID 已存在" }
                            if (e.old == null) store.ensureRole(e.account, zid, id, nick) else db.execSQL("UPDATE roles SET regionId=?,roleId=?,nickname=? WHERE id=?", arrayOf(zid, id.trim(), nick.trim(), e.old.optString("id")))
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }; null
                    } }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                    if (e.old != null) Action("删除角色及全部封禁历史") { confirm(Confirmation("删除角色？", "全部封禁历史和附件将被删除。") { Reminders.cancelAll(this@MainActivity, store); store.writableDatabase.delete("roles", "id=?", arrayOf(e.old.optString("id"))); saved(null) }) }
                }
            }
            is Editor.Ban -> BanEditor(e, store, dismiss, saved, notify)
        }
    }

    @Composable
    private fun BanEditor(e: Editor.Ban, store: Store, dismiss: () -> Unit, saved: (String?) -> Unit, notify: () -> Unit) {
        var start by remember { mutableLongStateOf(e.old?.optLong("start") ?: (System.currentTimeMillis() / 1000 * 1000)) }
        var end by remember { mutableLongStateOf(e.old?.optLong("end")?.takeIf { it > start } ?: (start + 86400000)) }
        var days by remember { mutableStateOf(((end - start) / 86400000).toString()) }
        var hours by remember { mutableStateOf(((end - start) / 3600000 % 24).toString()) }
        var minutes by remember { mutableStateOf(((end - start) / 60000 % 60).toString()) }
        var seconds by remember { mutableStateOf(((end - start) / 1000 % 60).toString()) }
        var permanent by remember { mutableStateOf(e.old?.optInt("permanent") == 1) }
        var mode by remember { mutableStateOf("填写封禁时长") }
        var reminder by remember { mutableStateOf(ReminderLabels[(e.old?.optInt("remind") ?: store.defaultReminder()).coerceIn(0, 3)]) }
        var reason by remember { mutableStateOf(e.old?.optString("reason") ?: "") }
        var note by remember { mutableStateOf(e.old?.optString("note") ?: "") }
        var error by remember { mutableStateOf("") }
        var copying by remember { mutableStateOf(false) }
        val added = remember { mutableStateListOf<File>() }
        val existing = remember { e.old?.let { store.query("SELECT * FROM attachments WHERE banId=?", it.optString("id")) } ?: emptyList() }
        val removed = remember { mutableStateListOf<String>() }
        val scope = rememberCoroutineScope()
        DisposableEffect(Unit) { onDispose { added.forEach { it.delete() } } }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) { copying = true; scope.launch {
                val staged = mutableListOf<File>()
                try { require(uris.size + added.size + existing.size - removed.size <= 20) { "每条记录最多 20 张图片" }
                    withContext(Dispatchers.IO) { uris.forEach { staged.add(copyImage(it)) } }; added.addAll(staged)
                } catch (x: Exception) { staged.forEach { it.delete() }; error = x.message ?: "添加失败" } finally { copying = false }
            } }
        }
        val calculated = if (permanent) 0L else if (mode == "填写预计解封时间") end else runCatching { BanTime.end(start, days.ifBlank { "0" }.toLong(), hours.ifBlank { "0" }.toLong(), minutes.ifBlank { "0" }.toLong(), seconds.ifBlank { "0" }.toLong()) }.getOrNull()
        EditorDialog(if (e.old == null) "新增封禁记录" else "编辑封禁记录", { if (!copying) dismiss() }) {
            val r = remember { store.one("SELECT r.roleId,r.nickname,g.name AS game,z.name AS region,z.platform FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.id=?", e.role) }
            r?.let { Text("${it.optString("game")} · ${it.location()} · ${it.roleLabel("roleId")}", color = Muted) }
            DateAction("封禁开始时间", start) { start = it }
            Toggle("永久封禁", permanent) { permanent = it }
            AnimatedVisibility(!permanent) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("填写方式", listOf("填写封禁时长", "填写预计解封时间"), mode) { next ->
                    if (next == "填写预计解封时间") end = calculated?.takeIf { it > start } ?: (start + 1000)
                    else { val s = ((end - start) / 1000).coerceAtLeast(1); days = (s / 86400).toString(); hours = (s % 86400 / 3600).toString(); minutes = (s % 3600 / 60).toString(); seconds = (s % 60).toString() }; mode = next
                }
                if (mode == "填写封禁时长") Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Numeric("天", days, Modifier.weight(1f)) { days = it }; Numeric("小时", hours, Modifier.weight(1f)) { hours = it } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Numeric("分钟", minutes, Modifier.weight(1f)) { minutes = it }; Numeric("秒", seconds, Modifier.weight(1f)) { seconds = it } }
                }
                else DateAction("预计解封时间", end) { end = it }
                Text(calculated?.let { "预计解封 ${formatted(it)}\n封禁时长 ${durationText(it - start)}" } ?: "请填写有效封禁时长", color = Gold)
                Choice("到期提醒", ReminderLabels, reminder) { reminder = it }
            } }
            Field("封禁原因（选填）", reason) { reason = it }; Field("备注（选填）", note, false) { note = it }
            Text("附件截图", color = Gold)
            existing.filterNot { removed.contains(it.optString("id")) }.forEach { a -> Screenshot(File(Backup.directory(this@MainActivity), a.optString("file"))); Action("移除此附件") { removed.add(a.optString("id")) } }
            added.toList().forEach { file -> Screenshot(file); Action("移除此附件") { added.remove(file); file.delete() } }
            Action(if (copying) "正在保存图片…" else "＋ 添加附件截图", !copying) { picker.launch(arrayOf("image/*")) }
            Error(error)
            Action("保存", !copying) {
                try {
                    require(start <= System.currentTimeMillis()) { "封禁开始时间不能晚于当前时间" }
                    val finalEnd = if (permanent) 0L else requireNotNull(calculated) { "请填写有效封禁时长" }
                    require(permanent || finalEnd > start) { "解封时间必须晚于封禁开始时间" }
                    require(e.old == null || e.old.optLong("confirmed") == 0L || start <= e.old.optLong("confirmed")) { "封禁开始时间不能晚于解封确认时间" }
                    requiredLength(reason); requiredLength(note)
                    val id = e.old?.optString("id") ?: Store.uuid()
                    require(store.one("SELECT id FROM bans WHERE roleId=? AND start=? AND end=? AND permanent=? AND id<>?", e.role, start.toString(), finalEnd.toString(), if (permanent) "1" else "0", id) == null) { "同一角色已存在相同时间的封禁" }
                    val db = store.writableDatabase; val moved = mutableListOf<Pair<File, File>>()
                    db.beginTransaction()
                    try {
                        // UPDATE preserves attachment relationships; REPLACE would cascade-delete them.
                        if (e.old == null) db.execSQL("INSERT INTO bans(id,roleId,start,end,permanent,confirmed,reason,note,remind,created) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(id, e.role, start, finalEnd, if (permanent) 1 else 0, 0, reason.trim(), note.trim(), if (permanent) 0 else ReminderLabels.indexOf(reminder), System.currentTimeMillis()))
                        else db.execSQL("UPDATE bans SET start=?,end=?,permanent=?,reason=?,note=?,remind=? WHERE id=?", arrayOf(start, finalEnd, if (permanent) 1 else 0, reason.trim(), note.trim(), if (permanent) 0 else ReminderLabels.indexOf(reminder), id))
                        removed.forEach { db.delete("attachments", "id=?", arrayOf(it)) }
                        added.forEach { source -> val name = Store.uuid() + ".img"; val target = File(Backup.directory(this@MainActivity), name); java.nio.file.Files.move(source.toPath(), target.toPath()); moved.add(source to target); db.execSQL("INSERT INTO attachments VALUES(?,?,?,?)", arrayOf(Store.uuid(), id, name, "image/*")) }
                        db.setTransactionSuccessful()
                    } catch (x: Exception) { moved.forEach { (source, target) -> runCatching { java.nio.file.Files.move(target.toPath(), source.toPath()) } }; throw x }
                    finally { db.endTransaction() }
                    saved(id)
                    if (!permanent && ReminderLabels.indexOf(reminder) > 0 && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notify()
                } catch (x: Exception) { error = x.message ?: "保存失败" }
            }
        }
    }

    @Composable
    private fun DateAction(label: String, value: Long, change: (Long) -> Unit) {
        var date by remember { mutableStateOf<LocalDate?>(null) }
        var hour by remember { mutableStateOf("") }
        var minute by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        Action("$label\n${formatted(value)}") {
            val z = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
            DatePickerDialog(this, { _, year, month, day ->
                hour = z.hour.toString().padStart(2, '0'); minute = z.minute.toString().padStart(2, '0'); second = z.second.toString().padStart(2, '0')
                error = ""; date = LocalDate.of(year, month + 1, day)
            }, z.year, z.monthValue - 1, z.dayOfMonth).show()
        }
        date?.let { selectedDate -> AlertDialog(onDismissRequest = { date = null }, title = { Text("$label · 时分秒") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedDate.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Numeric("时", hour, Modifier.weight(1f)) { hour = it; error = "" }
                    Numeric("分", minute, Modifier.weight(1f)) { minute = it; error = "" }
                    Numeric("秒", second, Modifier.weight(1f)) { second = it; error = "" }
                }
                Text("24 小时制：时 0–23，分、秒 0–59", color = Muted, fontSize = 12.sp)
                Error(error)
            } }, confirmButton = { TextButton(onClick = {
                val h = hour.toIntOrNull(); val m = minute.toIntOrNull(); val s = second.toIntOrNull()
                if (h == null || h !in 0..23 || m == null || m !in 0..59 || s == null || s !in 0..59) error = "请填写有效的时、分、秒"
                else { change(selectedDate.atTime(h, m, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()); date = null }
            }) { Text("确定") } }, dismissButton = { TextButton(onClick = { date = null }) { Text("取消") } }) }
    }
}

private sealed interface Editor {
    data class Account(val old: JSONObject? = null) : Editor
    data class Game(val old: JSONObject? = null) : Editor
    data class Role(val account: String, val old: JSONObject? = null) : Editor
    data class Region(val game: String, val old: JSONObject? = null) : Editor
    data class Ban(val role: String, val old: JSONObject? = null) : Editor
}
private class AvatarDraft {
    var file by mutableStateOf<File?>(null)
    var removed by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
}
private data class Confirmation(val title: String, val message: String, val action: () -> Unit)
private fun requiredLength(value: String) { require(value.length <= 10000) { "输入内容过长" } }
private fun required(value: String, label: String) { require(value.isNotBlank()) { "请填写$label" }; requiredLength(value) }
private fun durationText(millis: Long) = (millis / 1000).let { "${it / 86400}天 ${it % 86400 / 3600}小时 ${it % 3600 / 60}分钟 ${it % 60}秒" }
private fun remaining(b: JSONObject, now: Long = System.currentTimeMillis()): String = when (status(b, now)) {
    BanTime.ACTIVE -> BanTime.remaining(b.optLong("end"), now)
    BanTime.PERMANENT -> "永久封禁"; BanTime.CONFIRMED -> "已解封"; else -> "已到预计解封时间"
}

@Composable
private fun Avatar(fileName: String, label: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, fileName) {
        value = if (fileName.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = if (File(fileName).isAbsolute) File(fileName) else File(Backup.directory(context), fileName)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                var sample = 1
                while (options.outWidth / sample > 256 || options.outHeight / sample > 256) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                BitmapFactory.decodeFile(file.absolutePath, options)
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), "$label 头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(label.trim().take(1).ifBlank { "◈" }, color = Gold, fontSize = (size.value * 0.42f).sp)
    }
}
@Composable
private fun Heading(title: String, subtitle: String = "") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.headlineLarge); if (subtitle.isNotBlank()) Text(subtitle, color = Muted) } }
@Composable
private fun Action(label: String, enabled: Boolean = true, click: () -> Unit) { TextButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, modifier = Modifier.fillMaxWidth()) } }
@Composable
private fun Empty(message: String) { Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) { Text(message, color = Muted) } }
@Composable
private fun Field(label: String, value: String, single: Boolean = true, change: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = single, minLines = if (single) 1 else 3, modifier = Modifier.fillMaxWidth()) }
@Composable
private fun Numeric(label: String, value: String, modifier: Modifier, change: (String) -> Unit) { OutlinedTextField(value, { if (it.all(Char::isDigit)) change(it) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier) }
@Composable
private fun Error(error: String) { if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) }
@Composable
private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, change) } }
@Composable
private fun Choice(label: String, options: List<String>, selected: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$label · ${selected.ifBlank { "选择" }} ▾") }; DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { change(value); open = false }) } } }
}
@Composable
private fun PlatformField(platform: String, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(platform !in Platforms && platform.isNotBlank()) }
    val legacy by remember { mutableStateOf(platform.isBlank()) }
    Choice("账号平台", Platforms + listOf("自定义平台") + if (legacy) listOf("保留原区服") else emptyList(),
        if (custom) "自定义平台" else platform.ifBlank { "保留原区服" }) {
        custom = it == "自定义平台"
        change(when (it) { "自定义平台" -> if (platform !in Platforms) platform else ""; "保留原区服" -> ""; else -> it })
    }
    if (custom) Field("平台名称", platform) { change(it) }
    if (legacy && platform.isBlank() && !custom) Text("旧档案的平台尚未设置，原大区名称已保留。可在此补充平台。", color = Muted, fontSize = 12.sp)
}
@Composable
private fun EditorDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 720.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, color = Gold); Spacer(Modifier.height(14.dp)); Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content); TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") } }
        }
    }
}
@Composable
private fun BanCard(b: JSONObject, current: Boolean, now: Long, click: () -> Unit) {
    GoldPanel(Modifier.clickable(onClick = click)) { Text((if (current) "" else "历史 · ") + status(b, now), color = Gold); Text("${b.optString("game")} · ${b.location()}", fontSize = 21.sp); Text("${b.accountLabel(true)} / ${b.roleLabel()}", color = Muted); Text(remaining(b, now), color = Gold, fontSize = 25.sp) }
}
@Composable
private fun Screenshot(file: File) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.absolutePath) { value = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.absolutePath, options)
        var sample = 1; while (options.outWidth / sample > 1080 || options.outHeight / sample > 1440) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; BitmapFactory.decodeFile(file.absolutePath, options)
    } }
    bitmap?.let { Image(it.asImageBitmap(), "封禁截图", Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
}
