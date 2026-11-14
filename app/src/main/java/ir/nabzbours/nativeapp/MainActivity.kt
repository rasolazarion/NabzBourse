package ir.nabzbours.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val numberFormat = NumberFormat.getNumberInstance(Locale.US)
private fun fmt(v: Double?): String = v?.takeIf { it.isFinite() }?.let { numberFormat.format(it.toLong()) } ?: "—"
private fun objValue(o: JSONObject, vararg keys: String): Double? {
    for (k in keys) if (!o.isNull(k) && o.has(k)) return o.optDouble(k).takeIf { it.isFinite() }
    return null
}
private fun unwrap(o: JSONObject, key: String): JSONObject = o.optJSONObject(key) ?: o
private fun unwrapArray(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()

data class StockSummary(
    val symbol: String,
    val last: Double? = null,
    val close: Double? = null,
    val change: Double? = null,
    val error: String? = null
)
data class Metric(val label: String, val value: String, val positive: Boolean? = null)
data class StockDetail(
    val summary: StockSummary,
    val price: List<Metric>,
    val flow: List<Metric>,
    val money: List<Metric>,
    val technical: List<Metric>,
    val history: List<Double>,
    val supports: List<Double>,
    val resistances: List<Double>,
    val message: String
)


class TsetmcRepository {
    private val client = OkHttpClient()
    private val base = "https://webgw.tse.ir/InstrumentProvider/api/v1"

    private suspend fun request(path: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(base + path)
                .header("Accept", "application/json")
                .header("User-Agent", "NabzBourse/2.0")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    error("خطای سرور: ${response.code}")
                }
                response.body?.string() ?: error("پاسخ خالی از سرور")
            }
        }

    private fun number(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            val result = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            if (result != null) return result
        }
        return null
    }

    private fun text(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun value(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val nested = obj.optJSONObject(key)
            if (nested != null) {
                val v = number(nested, "value")
                if (v != null) return v
            }
        }
        return number(obj, *keys)
    }

    suspend fun fetch(symbol: String): StockDetail {
        val query = java.net.URLEncoder.encode(symbol, "UTF-8")
        val searchText = request(
            "/Instrument/InstrumentShortcut/fa?SimpleSearchText=$query&PageNumber=1&PageSize=30"
        )

        val search = JSONObject(searchText)
        val items = search.optJSONArray("items")
            ?: search.optJSONArray("Items")
            ?: JSONArray()

        if (items.length() == 0) error("نماد پیدا نشد: $symbol")

        var instrument: JSONObject? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = text(item, "instrument_Name", "instrumentName")
            if (name.trim() == symbol.trim()) {
                instrument = item
                break
            }
        }
        if (instrument == null) instrument = items.getJSONObject(0)

        val isin = text(instrument, "instrumentid", "instrumentId")
        if (isin.isBlank()) error("شناسه ISIN نماد پیدا نشد")

        val encodedIsin = java.net.URLEncoder.encode(isin, "UTF-8")

        val liveText = request(
            "/Instrument/LiveInstrumentByIdQuery/fa?InstrumentId=$encodedIsin"
        )
        val live = JSONObject(liveText)

        val clientText = try {
            request("/Instrument/InstrumentClientType/fa?InstrumentId=$encodedIsin")
        } catch (_: Exception) {
            "[]"
        }
        val clientRows = try {
            JSONArray(clientText)
        } catch (_: Exception) {
            JSONArray()
        }

        val historyText = request(
            "/History/Archive/fa?InstrumentId=$encodedIsin"
        )
        val historyJson = JSONArray(historyText)

        val historyRows = (0 until historyJson.length())
            .mapNotNull { historyJson.optJSONObject(it) }
            .sortedBy { text(it, "devenrlc", "tradeDate", "date") }

        val closes = historyRows.mapNotNull {
            number(it, "closingprice", "closingPrice")
        }.filter { it > 0 }

        val last = number(live, "lastPrice")
        val close = number(live, "closingPrice")
        val yesterday = number(live, "yesterdayPrice")

        val change = if (yesterday != null && yesterday != 0.0 && close != null) {
            (close - yesterday) / yesterday * 100
        } else {
            number(live, "closingPriceChangePercent")
        }

        var buyVolume = 0.0
        var sellVolume = 0.0
        var buyRealCount = 0.0
        var sellRealCount = 0.0
        var buyLegalVolume = 0.0
        var sellLegalVolume = 0.0

        for (i in 0 until clientRows.length()) {
            val row = clientRows.optJSONObject(i) ?: continue
            val title = text(row, "title")
            val buy = number(row, "buyAmount") ?: 0.0
            val sell = number(row, "sellAmount") ?: 0.0

            when {
                title.contains("حجم (حقیقی)") -> {
                    buyVolume = buy
                    sellVolume = sell
                }
                title.contains("حجم (حقوقی)") -> {
                    buyLegalVolume = buy
                    sellLegalVolume = sell
                }
                title.contains("تعداد (حقیقی)") -> {
                    buyRealCount = buy
                    sellRealCount = sell
                }
            }
        }

        val power = if (sellVolume > 0) buyVolume / sellVolume else null
        val netVolume = buyVolume - sellVolume
        val avg = if (closes.isNotEmpty()) closes.average() else null
        val rsi = calculateRsi(closes)
        val macd = calculateMacd(closes)
        val lows = pivotLevels(historyRows, true)
        val highs = pivotLevels(historyRows, false)

        val price = listOf(
            Metric("قیمت لحظه‌ای", fmt(last)),
            Metric("قیمت پایانی", fmt(close)),
            Metric("اولین قیمت", fmt(number(live, "firstPrice"))),
            Metric("بیشترین قیمت", fmt(number(live, "maxValue"))),
            Metric("کمترین قیمت", fmt(number(live, "minValue"))),
            Metric(
                "درصد تغییر",
                change?.let { "%+.2f٪".format(Locale.US, it) } ?: "—",
                change?.let { it >= 0 }
            ),
            Metric("ارزش معاملات", fmt(number(live, "tradeValue"))),
            Metric("حجم معاملات امروز", fmt(number(live, "tradeVolume"))),
            Metric("تعداد معاملات", fmt(number(live, "tradeCount"))),
            Metric("میانگین قیمت تاریخی", avg?.let { fmt(it) } ?: "—")
        )

        val flow = listOf(
            Metric("حجم خرید حقیقی", fmt(buyVolume), true),
            Metric("حجم فروش حقیقی", fmt(sellVolume), false),
            Metric("حجم خرید حقوقی", fmt(buyLegalVolume), true),
            Metric("حجم فروش حقوقی", fmt(sellLegalVolume), false),
            Metric("تعداد خریداران حقیقی", fmt(buyRealCount)),
            Metric("تعداد فروشندگان حقیقی", fmt(sellRealCount))
        )

        val money = listOf(
            Metric(
                "قدرت حجم خریدار به فروشنده",
                power?.let { "%.2f برابر".format(Locale.US, it) } ?: "—",
                power?.let { it >= 1 }
            ),
            Metric("خالص حجم حقیقی", (if (netVolume >= 0) "+" else "") + fmt(netVolume), netVolume >= 0),
            Metric("سرانه حجمی خرید حقیقی", if (buyRealCount > 0) fmt(buyVolume / buyRealCount) else "—"),
            Metric("سرانه حجمی فروش حقیقی", if (sellRealCount > 0) fmt(sellVolume / sellRealCount) else "—"),
            Metric("ارزش خرید حقیقی", "در دسترس نیست"),
            Metric("ارزش فروش حقیقی", "در دسترس نیست")
        )

        val technical = listOf(
            Metric("RSI", rsi?.let { "%.1f".format(Locale.US, it) } ?: "—", rsi?.let { it < 30 }),
            Metric("MACD", macd?.let { fmt(it) } ?: "—"),
            Metric("واگرایی RSI", "نیازمند داده بیشتر"),
            Metric("واگرایی MACD", "نیازمند داده بیشتر"),
            Metric("واگرایی قیمت و حجم", "نیازمند داده بیشتر"),
            Metric("حمایت اول و دوم", lows.take(2).joinToString(" / ") { fmt(it) }.ifBlank { "—" }, true),
            Metric("مقاومت اول و دوم", highs.take(2).joinToString(" / ") { fmt(it) }.ifBlank { "—" }, false),
            Metric(
                "وضعیت روند",
                if (closes.size > 1) {
                    if (closes.last() >= closes.first()) "صعودی" else "نزولی"
                } else "—"
            ),
            Metric("ریسک نوسان", "برآورد نشده")
        )

        return StockDetail(
            StockSummary(symbol, last, close, change),
            price,
            flow,
            money,
            technical,
            closes.takeLast(90),
            lows,
            highs,
            "داده‌ها از درگاه TSE دریافت شده‌اند. اطلاعات حقیقی و حقوقی در این نسخه بر اساس حجم و تعداد است، نه ارزش ریالی. شاخص‌ها محاسباتی‌اند و توصیه قطعی خرید یا فروش نیستند."
        )
    }

    private fun calculateRsi(a: List<Double>): Double? {
        if (a.size < 15) return null
        var gain = 0.0
        var loss = 0.0
        for (i in a.size - 14 until a.size) {
            val d = a[i] - a[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        return if (loss == 0.0) 100.0
        else 100.0 - 100.0 / (1.0 + gain / loss)
    }

    private fun calculateMacd(a: List<Double>): Double? {
        if (a.size < 26) return null
        fun ema(values: List<Double>, n: Int): Double {
            val k = 2.0 / (n + 1)
            var v = values.take(n).average()
            for (i in n until values.size) {
                v = values[i] * k + v * (1 - k)
            }
            return v
        }
        return ema(a, 12) - ema(a, 26)
    }

    private fun pivotLevels(rows: List<JSONObject>, low: Boolean): List<Double> {
        val values = rows.mapNotNull {
            number(
                it,
                if (low) "minValue" else "maxValue",
                if (low) "lowvalue" else "highvalue",
                "closingprice"
            )
        }

        val pivots = mutableListOf<Double>()
        for (i in 2 until values.size - 2) {
            val window = values.subList(i - 2, i + 3)
            if (
                if (low) values[i] == window.minOrNull()
                else values[i] == window.maxOrNull()
            ) {
                pivots.add(values[i])
            }
        }

        return pivots.distinct()
            .sortedWith(if (low) compareByDescending { it } else compareBy { it })
            .take(3)
    }
}


class StocksViewModel : ViewModel() {
    private val repo = TsetmcRepository()
    private val prefs = "nabz_native"
    var symbols by mutableStateOf(listOf("وبملت", "فولاد", "شستا"))
        private set
    var summaries by mutableStateOf<Map<String, StockSummary>>(emptyMap())
        private set
    var selected by mutableStateOf<StockDetail?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf("برای دریافت قیمت‌ها، اتصال را تازه‌سازی کنید.")
        private set
    var dark by mutableStateOf(false)
        private set
    var alerts by mutableStateOf<Map<String, Set<Int>>>(emptyMap())
        private set

    fun load(activity: android.content.Context) {
        val p = activity.getSharedPreferences(prefs, 0)
        symbols = p.getString("symbols", "وبملت,فولاد,شستا")!!.split(",").filter { it.isNotBlank() }.distinct()
        dark = p.getBoolean("dark", false)
        symbols.forEach { s -> alerts = alerts + (s to (0..3).toSet()) }
        refreshAll()
    }
    private fun persist(context: android.content.Context) {
        context.getSharedPreferences(prefs, 0).edit()
            .putString("symbols", symbols.joinToString(",")).putBoolean("dark", dark).apply()
    }
    
    fun toggleTheme(context: android.content.Context) { dark = !dark; persist(context) }
    fun addSymbol(s: String, context: android.content.Context) {
        val clean = s.trim()
        if (clean.isNotEmpty() && clean !in symbols) {
            symbols = symbols + clean
            alerts = alerts + (clean to (0..3).toSet())
            persist(context); refreshOne(clean)
        }
    }
    fun removeSymbol(s: String, context: android.content.Context) {
        symbols = symbols - s; summaries = summaries - s; alerts = alerts - s
        if (selected?.summary?.symbol == s) selected = null
        persist(context)
    }    fun toggleAlert(symbol: String, index: Int) {
        val set = alerts[symbol] ?: emptySet()
        alerts = alerts + (symbol to if (index in set) set - index else set + index)
    }

    fun refreshAll() {
        viewModelScope.launch {
            loading = true
            status = "در حال دریافت اطلاعات از TSETMC…"

            symbols.forEach { s ->
                try {
                    val d = repo.fetch(s)
                    summaries = summaries + (s to d.summary)
                } catch (e: Exception) {
                    val details = generateSequence(e) { it.cause }
                        .joinToString(" → ") {
                            "${it.javaClass.simpleName}: ${it.message ?: "بدون توضیح"}"
                        }
                    summaries = summaries + (s to StockSummary(s, error = details))
                }
            }

            status = "آخرین تلاش برای به‌روزرسانی انجام شد."
            loading = false
        }
    }

    private fun refreshOne(s: String) {
        viewModelScope.launch {
            try {
                val d = repo.fetch(s)
                summaries = summaries + (s to d.summary)
            } catch (e: Exception) {
                val details = generateSequence(e) { it.cause }
                    .joinToString(" → ") {
                        "${it.javaClass.simpleName}: ${it.message ?: "بدون توضیح"}"
                    }
                summaries = summaries + (s to StockSummary(s, error = details))
            }
        }
    }
    fun open(s: String) {
        viewModelScope.launch {
            loading = true; selected = null
            try { selected = repo.fetch(s) }
            catch (e: Exception) { status = "خطا در دریافت $s: ${e.message}" }
            loading = false
        }
    }
    fun back() { selected = null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: StocksViewModel = viewModel()
            LaunchedEffect(Unit) { vm.load(this@MainActivity) }
            val colors = if (vm.dark) darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF60A5FA),
                background = androidx.compose.ui.graphics.Color(0xFF090B0F),
                surface = androidx.compose.ui.graphics.Color(0xFF11151B),
                onSurface = androidx.compose.ui.graphics.Color(0xFFF5F7FA),
                onBackground = androidx.compose.ui.graphics.Color(0xFFF5F7FA)
            ) else lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF2563EB),
                background = androidx.compose.ui.graphics.Color(0xFFF4F6F9),
                surface = androidx.compose.ui.graphics.Color.White
            )
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (vm.selected == null) HomeScreen(vm, this@MainActivity)
                    else DetailScreen(vm, vm.selected!!)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: StocksViewModel, context: android.content.Context) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBarNative(
            title = "نبض بورس",
            subtitle = "دیده‌بان و تحلیل بازار",
            actions = {
                IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Default.Refresh, "تازه‌سازی") }
                IconButton(onClick = { vm.toggleTheme(context) }) { Icon(Icons.Default.Settings, "تنظیمات نمایش") }
            }
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                CardBlock {
                    Text("افزودن نماد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("مثال: خودرو") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.addSymbol(input, context); input = "" })
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { vm.addSymbol(input, context); input = "" }) {
                            Icon(Icons.Default.Add, "افزودن")
                        }
                    }
                }
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("دیده‌بان نمادها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (vm.loading) "در حال دریافت…" else "به‌روزرسانی دستی", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(vm.symbols, key = { it }) { symbol ->
                val q = vm.summaries[symbol]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { vm.open(symbol) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text("قیمت لحظه‌ای: ${fmt(q?.last)}  |  پایانی: ${fmt(q?.close)}", style = MaterialTheme.typography.bodySmall)
                            }
                            val color = when { q?.change == null -> MaterialTheme.colorScheme.onSurfaceVariant; q.change!! >= 0 -> androidx.compose.ui.graphics.Color(0xFF16A34A); else -> androidx.compose.ui.graphics.Color(0xFFDC2626) }
                            Text(q?.change?.let { "%+.2f٪".format(Locale.US, it) } ?: "—", color = color, fontWeight = FontWeight.Bold)
                        }
                        if (q?.error != null) Text(q.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${(vm.alerts[symbol] ?: emptySet()).size} هشدار فعال", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("صفحه سهم", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { vm.removeSymbol(symbol, context) }) { Icon(Icons.Default.Delete, "حذف نماد", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item {
                CardBlock {
                    Text("راهنمای رنگ‌ها", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("خرید / ورود پول", color = androidx.compose.ui.graphics.Color(0xFF16A34A))
                        Text("فروش / خروج پول", color = androidx.compose.ui.graphics.Color(0xFFDC2626))
                    }
                    Text("داده‌ها در صورت دسترسی سرویس TSETMC نمایش داده می‌شوند؛ عدد ساختگی جایگزین نمی‌شود.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(vm: StocksViewModel, detail: StockDetail) {
    BackHandler { vm.back() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.Default.ArrowBack, "بازگشت") }
                Column(Modifier.weight(1f)) {
                    Text("صفحه اختصاصی ${detail.summary.symbol}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("تابلو، جریان پول و تحلیل تکنیکال", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.open(detail.summary.symbol) }) { Icon(Icons.Default.Refresh, "تازه‌سازی") }
            }
            MetricSection("قیمت و معاملات", detail.price)
            CardBlock {
                Text("نمودار قیمت پایانی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                PriceChart(detail.history)
                Spacer(Modifier.height(8.dp))
                Text("حمایت: ${detail.supports.take(2).joinToString(" / ") { fmt(it) }.ifBlank { "نامشخص" }}", color = androidx.compose.ui.graphics.Color(0xFF16A34A), style = MaterialTheme.typography.bodySmall)
                Text("مقاومت: ${detail.resistances.take(2).joinToString(" / ") { fmt(it) }.ifBlank { "نامشخص" }}", color = androidx.compose.ui.graphics.Color(0xFFDC2626), style = MaterialTheme.typography.bodySmall)
                Text("سطوح با الگوریتم نقاط برگشت پنج‌روزه برآورد شده‌اند و قطعی نیستند.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MetricSection("خرید و فروش حقیقی و حقوقی", detail.flow)
            MetricSection("قدرت و جریان پول", detail.money)
            MetricSection("تحلیل تکنیکال و رفتار سهم", detail.technical)
            CardBlock {
                Text("جمع‌بندی سیستم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail.message, style = MaterialTheme.typography.bodyMedium)
            }
            CardBlock {
                Text("هشدارهای این نماد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val names = listOf("ورود پول هوشمند", "خروج سنگین پول", "حجم مشکوک", "فرصت خروج")
                names.forEachIndexed { i, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = i in (vm.alerts[detail.summary.symbol] ?: emptySet()),
                            onCheckedChange = { vm.toggleAlert(detail.summary.symbol, i) }
                        )
                        Text(name)
                    }
                }
                Text("این نسخه وضعیت انتخاب هشدارها را در نشست فعلی نگه می‌دارد؛ پایش خودکار شرط‌ها هنوز پیاده‌سازی نشده است.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("این برنامه ابزار نمایش داده و شاخص‌های محاسباتی است و سیگنال قطعی خرید یا فروش صادر نمی‌کند.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun MetricSection(title: String, metrics: List<Metric>) {
    CardBlock {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { metric ->
                    val color = when (metric.positive) {
                        true -> androidx.compose.ui.graphics.Color(0xFF16A34A)
                        false -> androidx.compose.ui.graphics.Color(0xFFDC2626)
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                    Column(
                        Modifier.weight(1f).padding(bottom = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(10.dp)
                    ) {
                        Text(metric.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp))
                        Text(metric.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PriceChart(values: List<Double>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    if (values.size < 2) {
        Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) { Text("داده تاریخی کافی برای نمودار موجود نیست.") }
        return
    }
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val minV = values.minOrNull() ?: 0.0
        val maxV = values.maxOrNull() ?: 1.0
        val range = max(1.0, maxV-minV)
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size-1).toFloat()
            val y = size.height - ((value-minV)/range).toFloat()*size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarNative(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = actions
    )
}
