package app.aravia.health

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports the user's daily health summaries and scores as CSV or JSON into cache/raw/ so Android's
 * share sheet can send them via FileProvider (same authority as raw .bin files).
 */
object DataExport {
    fun exportCsv(context: Context, history: HealthHistory): File {
        val dir = File(context.cacheDir, "raw").apply { mkdirs() }
        val out = File(dir, "aravia-resumo-diario.csv")
        val sb = StringBuilder()
        sb.appendLine("data,passos,calorias_ativas,calorias_total,fc_repouso,fc_media,fc_max,sono_min,sono_profundo_min,sono_rem_min,recuperacao,nota_sono,esforco,hrv_ms,estresse_medio,spo2_medio")
        for (d in history.days.keys.sorted()) {
            val h = history.day(d)
            val s = h?.summary
            val n = history.night(d)
            val sc = h?.score
            val cols = listOf(
                d.toString(),
                h?.steps?.toString() ?: "",
                s?.activeCalories?.toString() ?: "",
                s?.calories?.toString() ?: "",
                sc?.restingHR?.value?.toInt()?.toString() ?: s?.hrResting?.toString() ?: "",
                s?.hrAvg?.toString() ?: "",
                s?.hrMax?.toString() ?: "",
                n?.asleepMinutes?.toString() ?: "",
                n?.session?.deepMinutes?.toString() ?: "",
                n?.session?.remMinutes?.toString() ?: "",
                sc?.recovery?.score?.toString() ?: "",
                sc?.sleep?.score?.toString() ?: "",
                sc?.strain?.strain?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
                sc?.hrv?.rmssd?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
                s?.stressAvg?.toString() ?: sc?.stress?.average?.toInt()?.toString() ?: "",
                s?.spo2Avg?.toString() ?: sc?.spo2?.average?.toInt()?.toString() ?: "",
            )
            sb.appendLine(cols.joinToString(","))
        }
        out.writeText(sb.toString(), Charsets.UTF_8)
        return out
    }

    fun exportJson(context: Context, history: HealthHistory): File {
        val dir = File(context.cacheDir, "raw").apply { mkdirs() }
        val out = File(dir, "aravia-dados.json")
        val daysArr = JSONArray()
        for (d in history.days.keys.sorted()) {
            val h = history.day(d)
            val s = h?.summary
            val n = history.night(d)
            val sc = h?.score
            val obj = JSONObject()
                .put("day", d.toString())
                .put("steps", h?.steps ?: JSONObject.NULL)
                .put("activeCalories", s?.activeCalories ?: JSONObject.NULL)
                .put("calories", s?.calories ?: JSONObject.NULL)
                .put("restingHR", sc?.restingHR?.value ?: s?.hrResting ?: JSONObject.NULL)
                .put("hrAvg", s?.hrAvg ?: JSONObject.NULL)
                .put("hrMax", s?.hrMax ?: JSONObject.NULL)
                .put("sleepMinutes", n?.asleepMinutes ?: JSONObject.NULL)
                .put("recoveryScore", sc?.recovery?.score ?: JSONObject.NULL)
                .put("sleepScore", sc?.sleep?.score ?: JSONObject.NULL)
                .put("strain", sc?.strain?.strain ?: JSONObject.NULL)
                .put("hrvMs", sc?.hrv?.rmssd ?: JSONObject.NULL)
                .put("stressAvg", s?.stressAvg ?: sc?.stress?.average ?: JSONObject.NULL)
                .put("spo2Avg", s?.spo2Avg ?: sc?.spo2?.average ?: JSONObject.NULL)
            daysArr.put(obj)
        }
        val root = JSONObject()
            .put("version", 1)
            .put("algoVersion", app.aravia.analytics.ScoreEngine.ALGO_VERSION)
            .put("days", daysArr)
        out.writeText(root.toString(2), Charsets.UTF_8)
        return out
    }
}
