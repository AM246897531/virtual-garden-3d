package app.mishnehtorah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFF6F7FA)
private val Ink = Color(0xFF1D273B)
private val Accent = Color(0xFF316BDF)
private val DarkPaper = Color(0xFF111720)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialQuery = intent.getStringExtra("debug_query").orEmpty()
        setContent { RambamApp(initialQuery) }
    }
}

@Composable
fun RambamApp(initialQuery: String = "") {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reading_preferences", 0) }
    var dark by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var fontSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("font", 21f)) }
    var currentTab by rememberSaveable { mutableStateOf("search") }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var book by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableStateOf<Int?>(null) }
    var openLaw by remember { mutableStateOf<Law?>(null) }
    var store by remember { mutableStateOf<CorpusStore?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    val bookmarks = remember { mutableStateListOf<Int>() }
    var results by remember { mutableStateOf<List<Law>>(emptyList()) }

    LaunchedEffect(Unit) {
        bookmarks.clear()
        bookmarks.addAll(prefs.getStringSet("bookmarks", emptySet())!!.mapNotNull { it.toIntOrNull() })
        try {
            store = CorpusStore.open(context)
        } catch (ex: Exception) {
            loadingError = ex.message ?: "טעינת ספריית הרמב״ם נכשלה"
        }
    }
    LaunchedEffect(store, query) {
        val s = store
        results = if (s == null || query.isBlank()) emptyList()
        else withContext(Dispatchers.IO) { s.search(query) }
    }

    fun saveBookmarks() {
        prefs.edit().putStringSet("bookmarks", bookmarks.map { it.toString() }.toSet()).apply()
    }
    fun showLaw(law: Law) { openLaw = law }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF96B7FF), background = DarkPaper,
        surface = Color(0xFF1C2634), onBackground = Color.White)
    else lightColorScheme(
        primary = Accent, background = Paper, surface = Color.White,
        onBackground = Ink, onSurface = Ink)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (openLaw == null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 12.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                shadowElevation = 10.dp,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                            ) {
                                Row(
                                    Modifier.height(62.dp).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    listOf(
                                        Triple("search", Icons.Outlined.Search, "חיפוש"),
                                        Triple("library", Icons.Outlined.MenuBook, "ספרייה"),
                                        Triple("bookmarks", Icons.Outlined.BookmarkBorder, "שמורים"),
                                        Triple("settings", Icons.Outlined.Settings, "הגדרות")
                                    ).forEach { (tab, icon, label) ->
                                        val selected = currentTab == tab
                                        IconButton(
                                            onClick = { currentTab = tab },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                        ) {
                                            Icon(icon, contentDescription = label,
                                                tint = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        loadingError != null ->
                            Text("שגיאה בטעינת המאגר: " + loadingError,
                                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                        store == null ->
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("טוען את ספריית משנה תורה...")
                            }
                        openLaw != null ->
                            ReaderPage(
                                law = openLaw!!,
                                store = store!!,
                                bookmarked = bookmarks.contains(openLaw!!.id),
                                fontSize = fontSize,
                                onBack = { openLaw = null },
                                onNavigate = { showLaw(it) },
                                onBookmark = {
                                    val id = openLaw!!.id
                                    if (bookmarks.contains(id)) bookmarks.remove(id) else bookmarks.add(id)
                                    saveBookmarks()
                                },
                                onFontSize = {
                                    fontSize = it
                                    prefs.edit().putFloat("font", fontSize).apply()
                                })
                        currentTab == "search" ->
                            SearchPage(query, { query = it }, results, { showLaw(it) })
                        currentTab == "library" ->
                            LibraryPage(store!!, book, section, chapter,
                                onBack = {
                                    when {
                                        chapter != null -> chapter = null
                                        section != null -> section = null
                                        else -> book = null
                                    }
                                },
                                onBook = { book = it; section = null; chapter = null },
                                onSection = { section = it; chapter = null },
                                onChapter = { chapter = it },
                                onLaw = { showLaw(it) })
                        currentTab == "bookmarks" ->
                            BookmarksPage(store!!, bookmarks.toList(), { showLaw(it) })
                        else ->
                            SettingsPage(dark, {
                                dark = it
                                prefs.edit().putBoolean("dark", it).apply()
                            }, fontSize, {
                                fontSize = it
                                prefs.edit().putFloat("font", it).apply()
                            }, onClear = {
                                query = ""
                                prefs.edit().remove("last_query").apply()
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
}

@Composable
private fun SearchPage(
    query: String, onQueryChange: (String) -> Unit, results: List<Law>, onOpen: (Law) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(34.dp))
        PageHeading("משנה תורה", "הרמב״ם — חיפוש סמנטי עברי, ללא אינטרנט")
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text("שאל שאלה או חפש הלכה ברמב״ם...") },
            leadingIcon = { Icon(Icons.Outlined.Search, "חיפוש") },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, "נקה חיפוש")
                }
            },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(25.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface)
        )
        Spacer(Modifier.height(24.dp))
        if (query.isBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .065f)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("לימוד מתוך המקור", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(7.dp))
                    Text("מנוע הטמעות עברי מקומי משלב התאמת משמעות עם חיפוש מדויק. אפשר לחפש מילים, הלכות ונושאים בשפה טבעית. התוצאות מציגות את לשון הרמב״ם והמקור המדויק — לא פסק הלכה שנוצר בידי בינה מלאכותית.",
                        lineHeight = 25.sp)
                }
            }
        } else {
            Text("תוצאות מתוך משנה תורה", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (results.isEmpty()) {
                Text("לא נמצאה התאמה. נסה ניסוח קצר יותר או מונחים מלשון הרמב״ם.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 18.dp)) {
                itemsIndexed(results, key = { _, item -> item.id }) { index, law ->
                    LawResultCard(law, index == 0, onOpen)
                }
            }
        }
    }
}

@Composable
private fun LawResultCard(law: Law, first: Boolean = false, onOpen: (Law) -> Unit) {
    Surface(
        onClick = { onOpen(law) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(23.dp),
        shadowElevation = if (first) 4.dp else 1.dp
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(law.reference, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            Text(law.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                fontSize = 19.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text("פתח את ההלכה", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LibraryPage(
    store: CorpusStore, book: String?, section: String?, chapter: Int?,
    onBack: () -> Unit, onBook: (String) -> Unit, onSection: (String) -> Unit,
    onChapter: (Int) -> Unit, onLaw: (Law) -> Unit
) {
    val choices by produceState(initialValue = emptyList<String>(), store, book, section, chapter) {
        value = withContext(Dispatchers.IO) {
            when {
                book == null -> store.books()
                section == null -> store.sections(book)
                chapter == null -> store.chapters(book, section).map { "פרק " + it }
                else -> emptyList()
            }
        }
    }
    val chapterLaws by produceState(initialValue = emptyList<Law>(), store, book, section, chapter) {
        value = if (book != null && section != null && chapter != null) {
            withContext(Dispatchers.IO) { store.chapter(book, section, chapter) }
        } else emptyList()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (book != null) IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowForward, "חזור")
            }
            PageHeading("ספריית משנה תורה", "כל ספרי הרמב״ם, פרקים והלכות")
        }
        Spacer(Modifier.height(20.dp))
        Text(listOfNotNull(book, section, chapter?.let { "פרק " + it }).joinToString("  ·  "),
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chapter != null) {
                items(chapterLaws, key = { it.id }) { law -> LawResultCard(law, false, onLaw) }
            } else {
                items(choices, key = { it }) { label ->
                    Surface(onClick = {
                        when {
                            book == null -> onBook(label)
                            section == null -> onSection(label)
                            else -> onChapter(label.removePrefix("פרק ").toInt())
                        }
                    }, color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(19.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium)
                            Icon(Icons.Outlined.ChevronLeft, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksPage(store: CorpusStore, ids: List<Int>, onOpen: (Law) -> Unit) {
    val saved by produceState(initialValue = emptyList<Law>(), ids, store) {
        value = withContext(Dispatchers.IO) { store.findBookmarks(ids) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(30.dp))
        PageHeading("הלכות שמורות", "מקורות שבחרת לשמור במכשיר")
        Spacer(Modifier.height(24.dp))
        if (saved.isEmpty()) Text("עדיין לא שמרת הלכות. פתח הלכה ולחץ על סמל הסימנייה.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(saved, key = { it.id }) { law -> LawResultCard(law, onOpen = onOpen) }
        }
    }
}

@Composable
private fun ReaderPage(
    law: Law, store: CorpusStore, bookmarked: Boolean, fontSize: Float,
    onBack: () -> Unit, onNavigate: (Law) -> Unit, onBookmark: () -> Unit,
    onFontSize: (Float) -> Unit
) {
    val neighbors by produceState(initialValue = emptyList<Law>(), law, store) {
        value = withContext(Dispatchers.IO) { store.chapter(law.book, law.section, law.chapter) }
    }
    val index = neighbors.indexOfFirst { it.id == law.id }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowForward, "חזרה") }
            Text("משנה תורה", fontWeight = FontWeight.Bold)
            IconButton(onClick = onBookmark) {
                Icon(if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (bookmarked) "הסר סימנייה" else "שמור הלכה",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            Text(law.book, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(law.section, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("פרק " + law.chapter + " · הלכה " + law.number,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            Spacer(Modifier.height(29.dp))
            Text(law.text, fontFamily = FontFamily.Serif, fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.65f).sp)
            Spacer(Modifier.height(36.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("ההלכות הסמוכות בפרק", fontWeight = FontWeight.SemiBold)
            neighbors.drop((index - 1).coerceAtLeast(0)).take(3).forEach { nearby ->
                if (nearby.id != law.id) {
                    Spacer(Modifier.height(12.dp))
                    Text("הלכה " + nearby.number + ": " + nearby.text,
                        modifier = Modifier.clickable { onNavigate(nearby) },
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Serif, lineHeight = 26.sp)
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("א", fontSize = 15.sp)
            Slider(value = fontSize, onValueChange = onFontSize, valueRange = 17f..30f,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("א", fontSize = 26.sp)
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { if (index > 0) onNavigate(neighbors[index - 1]) },
                enabled = index > 0
            ) { Icon(Icons.Outlined.ChevronRight, "הלכה קודמת") }
            IconButton(
                onClick = { if (index >= 0 && index + 1 < neighbors.size) onNavigate(neighbors[index + 1]) },
                enabled = index >= 0 && index + 1 < neighbors.size
            ) { Icon(Icons.Outlined.ChevronLeft, "הלכה הבאה") }
        }
    }
}

@Composable
private fun SettingsPage(
    dark: Boolean, onDark: (Boolean) -> Unit,
    fontSize: Float, onFont: (Float) -> Unit, onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(15.dp))
        PageHeading("הגדרות", "חוויית לימוד אישית, מקומית ופרטית")
        Spacer(Modifier.height(30.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("מצב כהה", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(checked = dark, onCheckedChange = onDark)
                }
                Spacer(Modifier.height(26.dp))
                Text("גודל אותיות הלימוד", fontWeight = FontWeight.SemiBold)
                Slider(value = fontSize, onValueChange = onFont, valueRange = 17f..30f)
                Spacer(Modifier.height(15.dp))
                Text("כל ההלכות והחיפושים נשארים במכשיר. האפליקציה אינה מבקשת חיבור לאינטרנט.",
                    lineHeight = 25.sp)
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onClear) { Text("נקה חיפוש נוכחי") }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("מהדורת פיתוח · חיפוש מילות מפתח לא מקוון",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
    }
}