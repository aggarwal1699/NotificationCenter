package com.waterwatch

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AlertItem(val id: String, val status: String, val reporterName: String, val floor: String, val createdAt: String)

class WaterWatchApi(context: Context) {
    private val prefs = context.getSharedPreferences("waterwatch", Context.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()
    private var householdId: String? = prefs.getString("household_id", null)
    fun hasSession() = !prefs.getString("access_token", null).isNullOrBlank()
    private fun url(path: String) = BuildConfig.SUPABASE_URL.trimEnd('/') + path
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url(path)).header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        if (auth) prefs.getString("access_token", null)?.let { builder.header("Authorization", "Bearer $it") }
        else builder.header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
        if (body != null) builder.method(method, body.toString().toRequestBody(jsonType)) else builder.method(method, null)
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Server ${response.code}: $raw")
            if (raw.isBlank()) return JSONObject()
            val parsed = org.json.JSONTokener(raw).nextValue()
            return when (parsed) { is JSONObject -> parsed; is JSONArray -> JSONObject().put("items", parsed); else -> JSONObject().put("value", parsed) }
        }
    }
    fun signUp(email: String, password: String, name: String, floor: String): String {
        require(email.contains("@") && password.length >= 6 && name.isNotBlank()) { "Enter a valid email, name and password (6+ characters)." }
        val result = request("/auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password).put("data", JSONObject().put("name", name)), false)
        val user = result.optJSONObject("user") ?: result
        val id = user.optString("id")
        if (id.isNotBlank()) prefs.edit().putString("user_id", id).putString("display_name", name).putString("floor", floor).apply()
        val token = result.optString("access_token")
        if (token.isNotBlank()) prefs.edit().putString("access_token", token).apply()
        return if (token.isBlank()) "Account created. Verify your email, then sign in." else "Account created and signed in."
    }
    fun signIn(email: String, password: String): String {
        val result = request("/auth/v1/token?grant_type=password", "POST", JSONObject().put("email", email).put("password", password), false)
        val user = result.getJSONObject("user")
        val name = user.optJSONObject("user_metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: email.substringBefore("@")
        prefs.edit().putString("access_token", result.getString("access_token")).putString("user_id", user.getString("id")).putString("display_name", name).apply()
        return "Signed in. Create or join your household."
    }
    fun createHousehold(name: String, floor: String): String {
        val res = request("/rest/v1/rpc/create_household", "POST", JSONObject().put("p_name", name).put("p_floor", floor))
        val item = res.optJSONArray("items")?.optJSONObject(0) ?: res
        householdId = item.optString("household_id")
        prefs.edit().putString("household_id", householdId).putString("floor", floor).apply()
        prefs.getString("fcm_token", null)?.let { saveDeviceToken(it) }
        return "Household created. Invitation code: ${item.optString("invite_code")}"
    }
    fun joinHousehold(code: String, floor: String): String {
        require(code.isNotBlank()) { "Enter the invitation code." }
        val res = request("/rest/v1/rpc/join_household", "POST", JSONObject().put("p_code", code).put("p_floor", floor))
        householdId = res.optJSONArray("items")?.optJSONObject(0)?.optString("household_id") ?: res.optString("household_id")
        prefs.edit().putString("household_id", householdId).putString("floor", floor).apply()
        prefs.getString("fcm_token", null)?.let { saveDeviceToken(it) }
        return "Joined household successfully."
    }
    fun createAlert(): String {
        val hid = householdId ?: throw IllegalStateException("Create or join a household first.")
        val result = request("/rest/v1/rpc/create_water_alert", "POST", JSONObject().put("p_household_id", hid))
        val alertId = result.optJSONArray("items")?.optJSONObject(0)?.optString("alert_id") ?: result.optString("alert_id")
        if (alertId.isNotBlank()) runCatching { request("/functions/v1/push-alert", "POST", JSONObject().put("alert_id", alertId)) }
        return "Overflow alert created. Push delivery attempted for registered household devices."
    }
    fun getAlerts(): List<AlertItem> {
        val hid = householdId ?: return emptyList()
        val data = request("/rest/v1/alerts?household_id=eq.$hid&select=id,status,created_at,reporter_name,floor&order=created_at.desc&limit=30")
        val rows = data.optJSONArray("items") ?: JSONArray()
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            AlertItem(o.getString("id"), o.optString("status", "active"), o.optString("reporter_name", "Member"), o.optString("floor", "Other"), o.optString("created_at"))
        }
    }
    fun respond(id: String, kind: String, text: String): String {
        request("/rest/v1/rpc/respond_to_alert", "POST", JSONObject().put("p_alert_id", id).put("p_response_type", kind).put("p_message", text))
        runCatching { request("/functions/v1/push-alert", "POST", JSONObject().put("alert_id", id).put("event_type", "response")) }
        return "Response submitted."
    }
    fun resolve(id: String): String {
        request("/rest/v1/rpc/resolve_alert", "POST", JSONObject().put("p_alert_id", id))
        runCatching { request("/functions/v1/push-alert", "POST", JSONObject().put("alert_id", id).put("event_type", "resolved")) }
        return "Alert marked resolved."
    }
    fun saveDeviceToken(token: String) {
        val hid = householdId ?: return
        val uid = prefs.getString("user_id", null) ?: return
        val body = JSONArray().put(JSONObject().put("user_id", uid).put("household_id", hid).put("fcm_token", token))
        val req = Request.Builder().url(url("/rest/v1/user_devices?on_conflict=fcm_token")).header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${prefs.getString("access_token", "")}").header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates").post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { if (!it.isSuccessful) throw IllegalStateException("Device registration failed: ${it.code}") }
    }
    fun signOut() { prefs.edit().clear().apply(); householdId = null }
}
