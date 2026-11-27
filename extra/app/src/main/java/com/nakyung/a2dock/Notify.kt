package com.nakyung.a2dock

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Calendar
import java.util.GregorianCalendar

/** 출고 당일 아침 8시 40분 알림: "금일 한화오션 출고 오전 N대 · 오후 N대" */
object Notify {
    private const val CH = "out_plan"

    fun schedule(c: Context) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 40); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
            val pi = PendingIntent.getBroadcast(c, 840, Intent(c, OutAlarm::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val t = cal.timeInMillis
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: Exception) { }
    }

    private fun dayKey(day: String): Int {
        val m = Regex("(\\d{1,2})\\s*[/.]\\s*(\\d{1,2})").find(day) ?: return -1
        val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
        val cal = Calendar.getInstance()
        var y = cal.get(Calendar.YEAR)
        val cm = cal.get(Calendar.MONTH) + 1
        if (mo < cm - 6) y++ else if (mo > cm + 6) y--
        return y * 10000 + mo * 100 + d
    }

    /** 오늘 출고가 있으면 (제목, 내용) */
    fun todayText(state: String?): Pair<String, String>? {
        if (state.isNullOrEmpty()) return null
        val c = Calendar.getInstance()
        val tk = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        val arr = try { JSONObject(state).optJSONArray("P") } catch (e: Exception) { null } ?: return null
        var am = 0; var pm = 0
        val lines = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val tr = p.optJSONArray("tr") ?: continue
            var a = 0; var b = 0
            for (k in 0 until tr.length()) {
                val t = tr.getJSONObject(k)
                if (dayKey(t.optString("day")) != tk) continue
                val cars = t.optInt("c", 1)
                if (t.optInt("h", 9) < 12) a += cars else b += cars
            }
            if (a + b == 0) continue
            am += a; pm += b
            val dest = p.optString("d")
            lines.add(p.optString("b") + (if (dest.isNotBlank()) " → $dest" else "") + " (" +
                listOfNotNull(if (a > 0) "오전 ${a}대" else null, if (b > 0) "오후 ${b}대" else null).joinToString(" · ") + ")")
        }
        if (am + pm == 0) return null
        val title = "금일 한화오션 출고 " + listOfNotNull(if (am > 0) "오전 ${am}대" else null, if (pm > 0) "오후 ${pm}대" else null).joinToString(" · ")
        return title to lines.joinToString("\n")
    }

    fun show(c: Context, title: String, body: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "출고 일정 알림", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 841, Intent(c, MainActivity::class.java).putExtra("tab", "dock")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_truck)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(840, n) } catch (e: SecurityException) { }
    }
}

/** 8시 40분에 깨어나서: 서버(없으면 이 폰) 일정으로 오늘 출고 알림, 다음 날 알람 다시 맞춤 */
class OutAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        Thread {
            try {
                var st: String? = null
                try { if (Fb.enabled) st = JSONObject(Fb.get("dock")).optString("s") } catch (e: Exception) { }
                if (st.isNullOrEmpty()) st = c.getSharedPreferences("a2dock", Context.MODE_PRIVATE).getString("s", null)
                Notify.todayText(st)?.let { (t, b) -> Notify.show(c, t, b) }
            } catch (e: Exception) { }
            finally { Notify.schedule(c); pr.finish() }
        }.start()
    }
}

/** 폰 다시 켜면 알람 다시 맞춤 */
class BootRecv : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Notify.schedule(c) }
}
