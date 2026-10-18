package cn.chendian.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatted(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateFormat)
private fun status(b: JSONObject) = BanTime.state(b.optLong("start"), b.optLong("end"),
    b.optInt("permanent") == 1, b.optLong("confirmed"), System.currentTimeMillis())
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
        var editor by remember { mutableStateOf<Editor?>(null) }
        var confirmation by remember { mutableStateOf<Confirmation?>(null) }
        var managingGames by remember { mutableStateOf(false) }
        var restoreOpen by remember { mutableStateOf(false) }
        var replace by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf<String?>(null) }
        var preview by remember { mutableStateOf<File?>(null) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
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
        LaunchedEffect(Unit) { while (true) { delay(60_000); revision++ } }
        val latestPreview by rememberUpdatedState(preview)
        DisposableEffect(Unit) { onDispose { latestPreview?.delete() } }
        BackHandler(detail != null || role != null || tab != 0 || busy != null) {
            if (busy == null) when { detail != null -> detail = null; role != null -> role = null; else -> tab = 0 }
        }
        val bans = remember(revision) { store.bans().toList() }
        val current = remember(revision) { store.currentIds() }
        val games = remember(revision) { store.query("SELECT * FROM games ORDER BY name") }
        val accounts = remember(revision) { store.query("SELECT * FROM accounts ORDER BY type,CASE WHEN identifier='' THEN name ELSE identifier END") }
        LaunchedEffect(games, game) { if (game != "全部游戏" && games.none { it.optString("name") == game }) { game = "全部游戏"; store.setting("overviewGame", game) } }
        LaunchedEffect(accounts, overviewAccount) {
            if (overviewAccount.isNotEmpty() && accounts.none { it.optString("id") == overviewAccount }) {
                overviewAccount = ""; store.setting("overviewAccount", "")
            }
        }

        Scaffold(containerColor = Background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("总览", "账号", "导入", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, enabled = busy == null,
                        onClick = { tab = index; detail = null; role = null },
                        icon = { Text(listOf("◈", "◎", "↓", "⚙")[index], fontSize = 23.sp) }, label = { Text(title) })
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
                        item { BanCard(b, current.contains(id)) {} }
                        item { GoldPanel {
                            val values = listOf("账号" to b.accountLabel(true),
                                "昵称" to b.optString("nickname"), "封禁开始" to formatted(b.optLong("start")),
                                "预计解封" to if (b.optInt("permanent") == 1) "永久" else formatted(b.optLong("end")),
                                "封禁时长" to if (b.optInt("permanent") == 1) "永久" else ((b.optLong("end") - b.optLong("start")) / 60000).let { "${it / 1440}天 ${it % 1440 / 60}小时 ${it % 60}分钟" },
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
                        items(records, key = { it.optString("id") }) { BanCard(it, current.contains(it.optString("id"))) { detail = it.optString("id") } }
                    }
                    tab == 0 -> {
                        item { Heading("总览") }
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
                                        label = { Text("${a.title()} · ${a.optString("type")}" + if (a.optString("name").isNotBlank() && a.optString("identifier").isNotBlank()) " · ${a.optString("identifier")}" else "") })
                                }
                            } else {
                            (listOf("全部游戏") + games.map { it.optString("name") }).forEach { name -> FilterChip(selected = game == name, onClick = { game = name; store.setting("overviewGame", name) }, label = { Text(name) }) }
                            AssistChip(onClick = { editor = Editor.Name("添加游戏", "") { store.ensureGame(it) } }, label = { Text("＋ 游戏") })
                            }
                        } } }
                        // Each mode scopes independently; a previous game selection must not hide an account's other games.
                        val active = bans.filter { b -> current.contains(b.optString("id")) &&
                            if (byAccount) overviewAccount.isEmpty() || overviewAccount == b.optString("accountId")
                            else game == "全部游戏" || game == b.optString("game") }
                        item { GoldPanel { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val now = System.currentTimeMillis()
                            listOf("封禁中角色" to active.count { status(it) == BanTime.ACTIVE }, "今日预计解封" to active.count { it.optInt("permanent") == 0 && it.optLong("confirmed") == 0L && BanTime.today(it.optLong("end"), now, ZoneId.systemDefault()) }, "永久封禁" to active.count { status(it) == BanTime.PERMANENT }).forEach { (label, count) -> Column { Text("$count", fontSize = 28.sp, color = Gold); Text(label, fontSize = 11.sp, color = Muted) } }
                        } } }
                        item { OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索账号 / 角色 / 备注") }, shape = MaterialTheme.shapes.large) }
                        val needle = search.trim().lowercase(Locale.ROOT)
                        val records = active.filter { b ->
                            status(b) != BanTime.CONFIRMED &&
                                listOf("identifier", "accountName", "game", "platform", "region", "gameRoleId", "nickname", "note", "reason").joinToString(" ") { b.optString(it) }.lowercase(Locale.ROOT).contains(needle)
                        }.sortedWith(compareBy<JSONObject> { if (status(it) == BanTime.ACTIVE) it.optLong("end") else Long.MAX_VALUE }.thenByDescending { it.optLong("start") })
                        val groups = records.groupBy { it.optString("accountId") to it.optString("regionId") }.values.toList()
                        if (groups.isEmpty()) item { Empty(if (search.isBlank()) "暂无封禁账号" else "没有匹配的账号") }
                        items(groups, key = { rows -> rows.first().let { b -> "overview:${b.optString("accountId")}:${b.optString("regionId")}" } }) { rows ->
                            var expanded by remember(byAccount, game, overviewAccount) { mutableStateOf(false) }
                            val lead = rows.firstOrNull { status(it) == BanTime.ACTIVE } ?: rows.first()
                            GoldPanel(Modifier.animateContentSize().clickable { if (rows.size == 1) detail = lead.optString("id") else expanded = !expanded }) {
                                Text((if (byAccount) lead.optString("accountName").ifBlank { lead.optString("identifier") } else lead.optString("game")) + " · " + status(lead), color = Muted, fontSize = 12.sp)
                                Text(if (byAccount) lead.optString("game") else lead.optString("accountName").ifBlank { lead.optString("identifier") }, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Gold)
                                Text(lead.accountLabel(true), color = Muted)
                                rows.distinctBy { it.optString("roleId") }.take(2).forEach { Text("${it.location()} · ${it.roleLabel()}") }
                                val roleCount = rows.distinctBy { it.optString("roleId") }.size
                                if (roleCount > 2) Text("另有 ${roleCount - 2} 个角色", color = Muted, fontSize = 12.sp)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Text(remaining(lead), fontSize = 23.sp, color = Gold)
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
                                Column(Modifier.fillMaxWidth().clickable { account = if (account == id) null else id }) { Text(a.title(), fontSize = 25.sp, color = Gold); Text("${a.accountLabel()}  ${if (account == id) "▾" else "▸"}", color = Muted) }
                                AnimatedVisibility(account == id) { Column {
                                    Action("编辑账号") { editor = Editor.Account(a) }; Action("＋ 添加游戏角色") { editor = Editor.Role(id) }
                                    store.query("SELECT r.*,z.name AS region,z.platform,g.name AS game FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.accountId=? ORDER BY g.name,z.platform,z.name,r.nickname,r.roleId", id).forEach { r ->
                                        TextButton(onClick = { role = r.optString("id") }) { Text("${r.optString("game")} / ${r.location()}\n${r.roleLabel("roleId")}") }
                                    }
                                } }
                            }
                        }
                    }
                    tab == 2 -> {
                        item { Heading("导入") }
                        item { GoldPanel { Text("网页导入", fontSize = 22.sp, color = Gold); Text("识别待完善", color = Muted); Action("打开浏览器") { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) } } }
                        item { GoldPanel { Text("图片导入", fontSize = 22.sp, color = Gold); Text("识别待完善", color = Muted); Action("选择图片") { image.launch(arrayOf("image/*")) }; preview?.let { Screenshot(it) } } }
                        item { GoldPanel { Text("备份恢复", fontSize = 22.sp, color = Gold); Action("选择备份") { restoreOpen = true } } }
                    }
                    else -> {
                        item { Heading("设置") }
                        item { GoldPanel { Text("默认提醒", fontSize = 22.sp, color = Gold); Choice("提醒规则", ReminderLabels, ReminderLabels[store.defaultReminder().coerceIn(0, 3)]) { store.setting("defaultRemind", ReminderLabels.indexOf(it).toString()); refresh() }; Action("通知权限与管理") { notifications() } } }
                        item { GoldPanel { Text("备份与恢复", fontSize = 22.sp, color = Gold); Action("导出备份") { export.launch("封禁手册_${LocalDate.now()}.zip") }; Action("恢复备份") { restoreOpen = true } } }
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
        if (managingGames) EditorDialog("游戏管理", { managingGames = false }) {
            var expandedGame by rememberSaveable { mutableStateOf<String?>(null) }
            FilledTonalButton(onClick = { editor = Editor.Name("新增游戏", "") { store.ensureGame(it) } }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加游戏") }
            Text("展开游戏管理平台与大区；全平台互通的游戏无需填写大区。", color = Muted, style = MaterialTheme.typography.bodySmall)
            games.forEach { g ->
                val gid = g.optString("id")
                key(gid) { GoldPanel {
                    var menuOpen by remember { mutableStateOf(false) }
                    val regions = store.query("SELECT *,name AS region FROM regions WHERE gameId=? ORDER BY platform,name", gid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { expandedGame = if (expandedGame == gid) null else gid }.padding(vertical = 8.dp)) {
                            Text(g.optString("name") + if (expandedGame == gid) " ▾" else " ▸", color = Gold, fontSize = 20.sp)
                            Text("${regions.size} 项平台 / 大区", color = Muted, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("更多") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("重命名游戏") }, onClick = { menuOpen = false; editor = Editor.Name("重命名游戏", g.optString("name")) { store.writableDatabase.execSQL("UPDATE games SET name=? WHERE id=?", arrayOf(it, gid)) } })
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
        if (restoreOpen) EditorDialog("选择恢复方式", { restoreOpen = false }) {
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

    @Composable
    private fun Edit(e: Editor, store: Store, dismiss: () -> Unit, saved: (String?) -> Unit,
                     confirm: (Confirmation) -> Unit, notify: () -> Unit) {
        var error by remember { mutableStateOf("") }
        fun save(action: () -> String?) { try { saved(action()) } catch (x: Exception) { error = x.message ?: "保存失败" } }
        when (e) {
            is Editor.Name -> {
                var name by remember { mutableStateOf(e.initial) }
                EditorDialog(e.title, dismiss) { Field("名称（必填）", name) { name = it }; Error(error); Action("保存") { save { required(name, "名称"); e.save(name.trim()); null } } }
            }
            is Editor.Account -> {
                var type by remember { mutableStateOf(e.old?.optString("type") ?: "QQ") }
                var identifier by remember { mutableStateOf(e.old?.optString("identifier") ?: "") }
                var name by remember { mutableStateOf(e.old?.optString("name") ?: "") }
                EditorDialog(if (e.old == null) "添加账号" else "编辑账号", dismiss) {
                    Choice("登录类型", listOf("QQ", "微信"), type) { type = it; error = "" }
                    Field(if (type == "QQ") "QQ 号" else "微信号", identifier) { identifier = it; error = "" }
                    Field(if (type == "QQ") "QQ 昵称" else "微信昵称", name) { name = it; error = "" }
                    Text("号码和昵称至少填写一项，也可以都填。", color = Muted, fontSize = 12.sp)
                    Error(error); Action("保存") { save {
                        require(identifier.isNotBlank() || name.isNotBlank()) { if (type == "QQ") "请至少填写 QQ 号或 QQ 昵称" else "请至少填写微信号或微信昵称" }
                        requiredLength(identifier); requiredLength(name)
                        val exists = store.findAccount(type, identifier, name)
                        require(exists == null || exists.optString("id") == e.old?.optString("id")) { "该账号已存在" }
                        if (e.old == null) store.ensureAccount(type, identifier, name) else store.writableDatabase.execSQL("UPDATE accounts SET type=?,identifier=?,name=? WHERE id=?", arrayOf(type, identifier.trim(), name.trim(), e.old.optString("id")))
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
        var start by remember { mutableLongStateOf(e.old?.optLong("start") ?: (System.currentTimeMillis() / 60000 * 60000)) }
        var end by remember { mutableLongStateOf(e.old?.optLong("end")?.takeIf { it > start } ?: (start + 86400000)) }
        var days by remember { mutableStateOf(((end - start) / 86400000).toString()) }
        var hours by remember { mutableStateOf(((end - start) / 3600000 % 24).toString()) }
        var minutes by remember { mutableStateOf(((end - start) / 60000 % 60).toString()) }
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
        val calculated = if (permanent) 0L else if (mode == "填写预计解封时间") end else runCatching { BanTime.end(start, days.ifBlank { "0" }.toLong(), hours.ifBlank { "0" }.toLong(), minutes.ifBlank { "0" }.toLong()) }.getOrNull()
        EditorDialog(if (e.old == null) "新增封禁记录" else "编辑封禁记录", { if (!copying) dismiss() }) {
            val r = remember { store.one("SELECT r.roleId,r.nickname,g.name AS game,z.name AS region,z.platform FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.id=?", e.role) }
            r?.let { Text("${it.optString("game")} · ${it.location()} · ${it.roleLabel("roleId")}", color = Muted) }
            DateAction("封禁开始时间", start) { start = it }
            Toggle("永久封禁", permanent) { permanent = it }
            AnimatedVisibility(!permanent) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("填写方式", listOf("填写封禁时长", "填写预计解封时间"), mode) { next ->
                    if (next == "填写预计解封时间") end = calculated?.takeIf { it > start } ?: (start + 60000)
                    else { val m = ((end - start) / 60000).coerceAtLeast(1); days = (m / 1440).toString(); hours = (m % 1440 / 60).toString(); minutes = (m % 60).toString() }; mode = next
                }
                if (mode == "填写封禁时长") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Numeric("天", days, Modifier.weight(1f)) { days = it }; Numeric("小时", hours, Modifier.weight(1f)) { hours = it }; Numeric("分钟", minutes, Modifier.weight(1f)) { minutes = it } }
                else DateAction("预计解封时间", end) { end = it }
                Text(calculated?.let { "预计解封 ${formatted(it)}\n封禁时长 ${(it - start) / 86400000}天 ${(it - start) / 3600000 % 24}小时 ${(it - start) / 60000 % 60}分钟" } ?: "请填写有效封禁时长", color = Gold)
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
        Action("$label\n${formatted(value)}") {
            val z = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
            DatePickerDialog(this, { _, year, month, day -> TimePickerDialog(this, { _, hour, minute ->
                change(LocalDateTime.of(year, month + 1, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }, z.hour, z.minute, true).show() }, z.year, z.monthValue - 1, z.dayOfMonth).show()
        }
    }
}

private sealed interface Editor {
    data class Name(val title: String, val initial: String, val save: (String) -> Unit) : Editor
    data class Account(val old: JSONObject? = null) : Editor
    data class Role(val account: String, val old: JSONObject? = null) : Editor
    data class Region(val game: String, val old: JSONObject? = null) : Editor
    data class Ban(val role: String, val old: JSONObject? = null) : Editor
}
private data class Confirmation(val title: String, val message: String, val action: () -> Unit)
private fun requiredLength(value: String) { require(value.length <= 10000) { "输入内容过长" } }
private fun required(value: String, label: String) { require(value.isNotBlank()) { "请填写$label" }; requiredLength(value) }
private fun remaining(b: JSONObject): String = when (status(b)) {
    BanTime.ACTIVE -> BanTime.remaining(b.optLong("end"), System.currentTimeMillis())
    BanTime.PERMANENT -> "永久封禁"; BanTime.CONFIRMED -> "已解封"; else -> "已到预计解封时间"
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
private fun BanCard(b: JSONObject, current: Boolean, click: () -> Unit) {
    GoldPanel(Modifier.clickable(onClick = click)) { Text((if (current) "" else "历史 · ") + status(b), color = Gold); Text("${b.optString("game")} · ${b.location()}", fontSize = 21.sp); Text("${b.accountLabel(true)} / ${b.roleLabel()}", color = Muted); Text(remaining(b), color = Gold, fontSize = 25.sp) }
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
