package app.mishnehtorah

import android.os.Bundle
import android.util.Log
import org.json.JSONArray
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The brand palette is shared by every screen; the retrieval engine is unchanged.
private val Paper = Color(0xFFFFFFFF)
private val Cream = Color(0xFFF4E9D5)
private val Ink = Color(0xFF252420)
private val Accent = Color(0xFF936D34) // Contrast-safe antique gold for small text.
private val AntiqueGold = Color(0xFFB38C4F)
private val Turquoise = Color(0xFFBCEBE4)
private val TealInk = Color(0xFF217B70)
private val DarkPaper = Color(0xFF211D18)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialQuery = intent.getStringExtra("debug_query").orEmpty()
        val debugTypeahead = intent.getBooleanExtra("debug_typeahead", false)
        val debugQuality = intent.getBooleanExtra("debug_quality_50", false)
        val debugQuality100 = intent.getBooleanExtra("debug_quality_100", false)
        setContent { RambamApp(initialQuery, debugTypeahead,
            debugQuality || debugQuality100, debugQuality100) }
    }
}

@Composable
fun RambamApp(initialQuery: String = "", debugTypeahead: Boolean = false,
              debugQuality: Boolean = false, debugQuality100: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reading_preferences", 0) }
    var dark by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var fontSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("font", 21f)) }
    var currentTab by rememberSaveable { mutableStateOf("search") }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var submittedQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var searchRefresh by rememberSaveable { mutableIntStateOf(0) }
    val searchMutex = remember { Mutex() }
    var book by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableStateOf<Int?>(null) }
    var openLaw by remember { mutableStateOf<Law?>(null) }
    var sourceQuestion by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<CorpusStore?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    val bookmarks = remember { mutableStateListOf<Int>() }
    var results by remember { mutableStateOf<List<Law>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bookmarks.clear()
        bookmarks.addAll(prefs.getStringSet("bookmarks", emptySet())!!.mapNotNull { it.toIntOrNull() })
        try {
            store = CorpusStore.open(context)
        } catch (ex: Exception) {
            loadingError = ex.message ?: "טעינת ספריית הרמב״ם נכשלה"
        }
    }
    // Do not run heavyweight SQLite+semantic retrieval for each keystroke.
    // Delayed autosubmit preserves the previous instant-search experience,
    // while IME Search and the visible button submit immediately.
    LaunchedEffect(query) {
        delay(380)
        submittedQuery = query.trim()
    }
    LaunchedEffect(debugTypeahead) {
        if (debugTypeahead) {
            val testQuestion = "מה עושים אם שכחתי יעלה ויבוא בברכת המזון"
            query = ""
            submittedQuery = ""
            for (position in testQuestion.indices) {
                query = testQuestion.substring(0, position + 1)
                delay(28)
            }
            android.util.Log.i("RambamSearch", "TYPEAHEAD_INPUT_COMPLETE")
        }
    }
    // Real Android end-to-end regression suite, not a mocked Python search.
    // On the emulator this runs exactly the same CorpusStore.search and
    // RelevantPassage methods used by the user-facing Compose screens.
    LaunchedEffect(store, debugQuality) {
        val engine = store
        if (debugQuality && engine != null) {
            withContext(Dispatchers.IO) {
                try {
                    // Two distinct regression sets: the old curated questions
                    // AND an unseen cross-section set. Report exact Top-1 accuracy;
                    // never silently count semantic proximity as a correct source.
                    val suite = if (debugQuality100)
                        listOf("quality_natural_100.json" to "Q100")
                    else listOf("quality_full_110.json" to "Q110")
                    for ((asset, prefix) in suite) {
                        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
                        val cases = JSONArray(raw)
                        val timings = ArrayList<Long>()
                        var passed = 0
                        var highlighted = 0
                        var goldAnchors = 0
                        var verifiedAnchors = 0
                        for (index in 0 until cases.length()) {
                            val item = cases.getJSONObject(index)
                            val question = item.getString("question")
                            val section = "הלכות " + item.getString("section")
                            val expectedChapter = item.getInt("chapter")
                            val expectedLaw = item.getInt("law")
                            val begin = android.os.SystemClock.elapsedRealtime()
                            val answers = engine.search(question)
                            timings.add(android.os.SystemClock.elapsedRealtime() - begin)
                            val top = answers.firstOrNull()
                            val accurate = top != null && top.section.endsWith(section) &&
                                top.chapter == expectedChapter && top.number == expectedLaw
                            if (accurate) passed++
                            val passage = if (top != null) engine.relevantPassage(top, question) else null
                            val required = item.optString("highlight_contains", "")
                            val boundsValid = passage != null && top != null &&
                                passage.start >= 0 && passage.end <= top.text.length &&
                                passage.end > passage.start &&
                                // Painting the whole long halakhah is never a
                                // successful precise answer highlight.
                                passage.end-passage.start <= 360
                            val hasGoldAnchor = required.isNotBlank()
                            if (hasGoldAnchor) goldAnchors++
                            val goldStart=item.optInt("highlight_start_utf16",-1)
                            val goldEnd=item.optInt("highlight_end_utf16",-1)
                            val goldAvailable=goldStart>=0 && goldEnd>goldStart
                            val overlap=if (boundsValid && goldAvailable)
                                (minOf(passage!!.end,goldEnd)-maxOf(passage!!.start,goldStart))
                                    .coerceAtLeast(0) else 0
                            val goldCoverage=if (goldAvailable)
                                overlap.toFloat()/(goldEnd-goldStart) else 0f
                            val highlightPrecision=if (boundsValid)
                                overlap.toFloat()/(passage!!.end-passage!!.start) else 0f
                            // Reject painting a broad paragraph that merely contains
                            // a small expected phrase. Original UTF-16 source offsets
                            // are independently computed from corpus text at build time.
                            val offsetAccurate=goldAvailable &&
                                goldCoverage>=.90f && highlightPrecision>=.20f
                            val anchorPresent=boundsValid && hasGoldAnchor &&
                                HebrewSearch.normalize(top!!.text.substring(passage!!.start, passage!!.end))
                                    .contains(HebrewSearch.normalize(required))
                            val anchorValid=anchorPresent &&
                                (!debugQuality100 || offsetAccurate)
                            // Legacy source-quality and new strict exact offset score
                            // are kept independent, so weak highlight checks cannot
                            // masquerade as measured 100% correctness.
                            val passageValid=boundsValid && (!hasGoldAnchor || anchorValid)
                            if (accurate && passageValid) highlighted++
                            if (accurate && anchorValid) verifiedAnchors++
                            val excerpt = if (boundsValid)
                                top!!.text.substring(passage!!.start, passage!!.end)
                                    .replace('\n', ' ').take(130) else "none"
                            Log.i("RambamQuality", "${prefix}_CASE ${index + 1}/${cases.length()} " +
                                "${if (accurate) "PASS" else "FAIL"} tier=${item.getInt("tier")} " +
                                "top=${top?.reference ?: "none"} " +
                                "expected=$section/$expectedChapter/$expectedLaw " +
                                "passage=$passageValid anchor=$anchorValid " +
                                "goldCoverage=$goldCoverage highlightPrecision=$highlightPrecision " +
                                "spanLength=${if (passage!=null) passage.end-passage.start else -1} " +
                                "elapsed_ms=${timings.last()} " +
                                "excerpt=$excerpt")
                        }
                        val sorted = timings.sorted()
                        val median = sorted[sorted.size / 2]
                        val p95 = sorted[((sorted.size * .95).toInt()).coerceAtMost(sorted.lastIndex)]
                        Log.i("RambamQuality", "${prefix}_COMPLETE passed=$passed total=${cases.length()} " +
                            "highlighted=$highlighted verifiedAnchors=$verifiedAnchors goldAnchors=$goldAnchors " +
                            "median_ms=$median p95_ms=$p95 " +
                            "max_ms=${sorted.last()}")
                    }
                } catch (error: Throwable) {
                    Log.e("RambamQuality", if (debugQuality100) "Q100_FAILED_TO_RUN" else "Q110_FAILED_TO_RUN", error)
                }
            }
        }
    }
    LaunchedEffect(store, submittedQuery, searchRefresh) {
        val s = store
        searchError = null
        if (s == null || submittedQuery.isBlank()) {
            searching = false
            results = emptyList()
        } else {
            searching = true
            try {
                // Serialize DB and model searches, and never mistake a cancelled
                // stale request for a search error on the latest typed question.
                val fresh = withContext(Dispatchers.IO) {
                    searchMutex.withLock { s.search(submittedQuery) }
                }
                results = fresh
                searching = false
                if (debugTypeahead && query == "מה עושים אם שכחתי יעלה ויבוא בברכת המזון") {
                    android.util.Log.i("RambamSearch", "TYPEAHEAD_RESULT_READY count=${fresh.size}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                results = emptyList()
                searchError = "החיפוש נכשל (${ex.javaClass.simpleName}). אפשר לנסות שוב."
                searching = false
                android.util.Log.e("RambamSearch", "Search query failed", ex)
            }
        }
    }

    fun submitSearch() {
        submittedQuery = query.trim()
        searchRefresh += 1
    }

    fun saveBookmarks() {
        prefs.edit().putStringSet("bookmarks", bookmarks.map { it.toString() }.toSet()).apply()
    }
    fun showLaw(law: Law, fromSearch: Boolean = false) {
        openLaw = law
        sourceQuestion = if (fromSearch) submittedQuery else ""
    }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFE3C18B), secondary = Color(0xFF91DAD0),
        tertiary = AntiqueGold, background = DarkPaper,
        surface = Color(0xFF352D23), surfaceVariant = Color(0xFF44382A),
        onBackground = Color(0xFFF9F1E4), onSurface = Color(0xFFF9F1E4),
        outline = Color(0xFF806B4E))
    else lightColorScheme(
        primary = Accent, secondary = TealInk, tertiary = AntiqueGold,
        background = Paper, surface = Color(0xFFFFFAF1), surfaceVariant = Cream,
        onBackground = Ink, onSurface = Ink, outline = Color(0xFFD3BD99))

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
                                                .background(if (selected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f) else Color.Transparent)
                                        ) {
                                            Icon(icon, contentDescription = label,
                                                tint = if (selected) MaterialTheme.colorScheme.secondary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = .85f))
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
                                question = sourceQuestion,
                                onBack = { openLaw = null },
                                onNavigate = { showLaw(it, fromSearch = sourceQuestion.isNotBlank()) },
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
                            SearchPage(query, submittedQuery, store!!,
                                { query = it }, results, searching, searchError,
                                { submitSearch() }, { showLaw(it, fromSearch = true) })
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
    query: String, submittedQuery: String, store: CorpusStore,
    onQueryChange: (String) -> Unit, results: List<Law>,
    searching: Boolean, searchError: String?, onSearch: () -> Unit,
    onOpen: (Law) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(34.dp))
        PageHeading("הלכות הרמב״ם", "משנה תורה — חיפוש עברי חכם, ללא אינטרנט")
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text("שאל שאלה או חפש הלכה ברמב״ם...") },
            leadingIcon = { Icon(Icons.Outlined.Search, "חיפוש") },
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = query.isNotBlank()) {
                    Icon(Icons.Outlined.ArrowBack, "חפש שאלה")
                }
            },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(25.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.secondary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.secondary,
                focusedLeadingIconColor = MaterialTheme.colorScheme.tertiary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.tertiary)
        )
        Spacer(Modifier.height(24.dp))
        if (query.isBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("לימוד מתוך המקור", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(7.dp))
                    Text("אפשר להקליד שאלה הלכתית קצרה. מנוע עברי מקומי ישלב התאמה סמנטית עם חיפוש מקור מדויק, ויציג את לשון הרמב״ם במלואה, ללא יצירת פסק חדש.",
                        lineHeight = 25.sp)
                }
            }
        } else {
            Text("התאמה חכמה לטקסט הרמב״ם", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (searching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("מאתר הלכות מתאימות...")
                }
            } else if (searchError != null) {
                Text(searchError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onSearch) { Text("נסה שוב") }
            } else if (results.isEmpty()) {
                Text("לא נמצאה התאמה. נסה ניסוח קצר יותר או מונחים מלשון הרמב״ם.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
            LazyColumn(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 18.dp)) {
                itemsIndexed(results, key = { _, item -> item.id }) { index, law ->
                    LawResultCard(law, index == 0, onOpen,
                        store = store, question = submittedQuery)
                }
            }
        }
    }
}

/** Preserve the Rambam's exact text and add only visual evidence highlighting. */
@Composable
private fun HighlightedRambamText(
    text: String, range: PassageMatch?, fontSize: Float,
    lineHeight: Float, maxLines: Int = Int.MAX_VALUE,
    onLayout: ((TextLayoutResult) -> Unit)? = null
) {
    val highlightColor = if (MaterialTheme.colorScheme.background == DarkPaper)
        Color(0xFF285C51) else Turquoise
    val marked = remember(text, range, highlightColor) {
        buildAnnotatedString {
            append(text)
            if (range != null && range.start in text.indices &&
                range.end in (range.start + 1)..text.length) {
                addStyle(SpanStyle(background = highlightColor,
                    fontWeight = FontWeight.SemiBold), range.start, range.end)
            }
        }
    }
    Text(marked, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, fontSize = fontSize.sp,
        lineHeight = lineHeight.sp, fontFamily = FontFamily.Serif,
        onTextLayout = onLayout ?: {})
}

@Composable
private fun LawResultCard(
    law: Law, first: Boolean = false, onOpen: (Law) -> Unit,
    store: CorpusStore? = null, question: String = ""
) {
    val passage = remember(law.id, question, store) {
        if (store != null && question.isNotBlank()) store.relevantPassage(law, question) else null
    }
    Surface(
        onClick = { onOpen(law) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(23.dp),
        shadowElevation = if (first) 4.dp else 1.dp
    ) {
        Column(Modifier.padding(20.dp)) {
            if (first) {
                Text("המקור המתאים ביותר • לשון הרמב״ם", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
            }
            Text(law.reference, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            HighlightedRambamText(law.text, passage,
                fontSize = 19f, lineHeight = 29f,
                maxLines = if (first) Int.MAX_VALUE else 4)
            if (passage != null) {
                Spacer(Modifier.height(7.dp))
                Text("הקטע המודגש הוא התאמה משוערת לשאלה — יש לקרוא גם את ההקשר",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
            }
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
    question: String,
    onBack: () -> Unit, onNavigate: (Law) -> Unit, onBookmark: () -> Unit,
    onFontSize: (Float) -> Unit
) {
    val neighbors by produceState(initialValue = emptyList<Law>(), law, store) {
        value = withContext(Dispatchers.IO) { store.chapter(law.book, law.section, law.chapter) }
    }
    val index = neighbors.indexOfFirst { it.id == law.id }
    val passage = remember(law.id, question, store) { store.relevantPassage(law, question) }
    val scroll = rememberScrollState()
    var passageTop by remember(law.id, question) { mutableFloatStateOf(-1f) }
    val density = LocalDensity.current
    LaunchedEffect(law.id, passageTop) {
        if (passage != null && passageTop >= 0f) {
            val offset = with(density) { 150.dp.toPx() }
            scroll.animateScrollTo((offset + passageTop).toInt().coerceAtLeast(0))
        } else if (passage == null) scroll.scrollTo(0)
    }
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
            Modifier.weight(1f).verticalScroll(scroll)
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
            HighlightedRambamText(law.text, passage, fontSize = fontSize,
                lineHeight = fontSize * 1.65f,
                onLayout = { layout ->
                    if (passage != null && passage.start in law.text.indices) {
                        passageTop = layout.getBoundingBox(passage.start).top
                    }
                })
            if (passage != null) {
                Spacer(Modifier.height(10.dp))
                Text("ההדגשה מציינת קטע שאותר כשייך לשאלה. אין להסתמך עליה בלי לקרוא את ההלכה.",
                    style = MaterialTheme.typography.labelSmall)
            }
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
        Text("מהדורת פיתוח · חיפוש סמנטי מקומי + הדגשת מקור",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
    }
}
