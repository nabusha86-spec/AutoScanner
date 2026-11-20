package com.autoscanner.trader.analytics

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

class ForecastHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, HISTORY_FILE)

    suspend fun append(prediction: PredictionResult, qualitySummary: String) =
        withContext(Dispatchers.IO) {
            val atomicFile = AtomicFile(file)
            val history = if (file.exists() || File("${file.path}.bak").exists()) {
                try {
                    JSONArray(atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                } catch (error: JSONException) {
                    throw IOException("История прогнозов повреждена; файл сохранён без изменений", error)
                }
            } else JSONArray()
            val record = JSONObject()
                .put("analysisTimeMillis", prediction.analysisTimeMillis)
                .put("instrument", prediction.instrument ?: JSONObject.NULL)
                .put("prediction", prediction.direction.name)
                .put("confidence", prediction.confidence)
                .put("expirationSeconds", prediction.expiration.seconds ?: JSONObject.NULL)
                .put("timeframe", prediction.timeframe ?: JSONObject.NULL)
                .put("marketCondition", prediction.marketCondition.name)
                .put("reasons", JSONArray(prediction.reasons))
                .put("risks", JSONArray(prediction.risks.map { it.description }))
                .put("factors", JSONArray(prediction.factors.map { factor ->
                    JSONObject()
                        .put("name", factor.name)
                        .put("value", factor.value)
                        .put("influence", factor.influence.name)
                        .put("weight", factor.weight)
                        .put("confidence", factor.confidence)
                        .put("source", factor.source)
                }))
                .put("quality", qualitySummary)
                .put("outcome", JSONObject.NULL)
            history.put(record)
            val output = atomicFile.startWrite()
            try {
                output.write(history.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (error: IOException) {
                atomicFile.failWrite(output)
                throw error
            }
        }

    companion object {
        private const val HISTORY_FILE = "forecast_history.json"
    }
}
