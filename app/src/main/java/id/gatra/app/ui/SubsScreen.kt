package id.gatra.app.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.gatra.app.GatraApp
import id.gatra.app.data.SubscriptionEntity
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as GatraApp).repo

    val subs: StateFlow<List<SubscriptionEntity>> =
        repo.subscriptions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var feed by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        // Muat ulang feed setiap daftar langganan berubah.
        viewModelScope.launch { subs.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = feed.isEmpty()
            refreshing = true
            try {
                feed = repo.feed()
                launch { repo.backfillAvatars() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = friendlyError(e)
            }
            refreshing = false
            loading = false
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = repo.exportCsv()
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
                message = "Langganan berhasil diekspor."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Gagal mengekspor: ${e.message}"
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
                }
                val n = repo.importCsv(text)
                message = if (n > 0) "$n channel diimpor." else "Tidak ada channel yang ditemukan di berkas itu."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Gagal mengimpor: ${e.message}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubsScreen(
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    vm: SubsViewModel = viewModel()
) {
    val subs by vm.subs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = { Text("Langganan", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Muat ulang", tint = G.Text) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = G.Text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Impor langganan (CSV)") }, onClick = {
                        menu = false
                        importLauncher.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                    })
                    DropdownMenuItem(text = { Text("Ekspor langganan (CSV)") }, onClick = {
                        menu = false
                        exportLauncher.launch("gatra-langganan.csv")
                    })
                }
            }
        )

        if (subs.isEmpty()) {
            EmptyState(
                "Belum ada langganan",
                "Buka halaman channel lalu tekan Langganan. Langganan disimpan di HP ini, tanpa akun. " +
                    "Punya daftar dari Google Takeout? Impor berkas subscriptions.csv lewat menu titik tiga."
            )
        } else {
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(subs) { s ->
                                Column(
                                    Modifier.width(68.dp).clickable { onChannel(s.url) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    InitialAvatar(s.name, 56.dp, imageUrl = s.avatar)
                                    Text(
                                        s.name, style = MaterialTheme.typography.bodySmall, color = G.Muted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    item { SectionTitle("Video terbaru") }
                    if (vm.loading && vm.feed.isEmpty()) {
                        item { LoadingBox() }
                    } else if (vm.feed.isEmpty()) {
                        item { EmptyState("Belum ada video baru", "Tarik layar ke bawah untuk memeriksa lagi.") }
                    } else {
                        items(vm.feed) { v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                }
            }
        }
    }
}
