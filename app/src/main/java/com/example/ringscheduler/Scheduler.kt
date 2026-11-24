package com.example.ringscheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/** days use Calendar constants: SUNDAY=1 ... SATURDAY=7 */
data class Rule(val id: Long, val days: Set<Int>, val hour: Int, val minute: Int, val percent: Int) {
    fun label(): String {
        val names = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val d = days.sorted().joinToString(", ") { names[it] }
        return "%s  %02d:%02d  →  %d%%".format(d, hour, minute, percent)
    }
}

object RuleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(c: Context): List<Rule> {
        val arr = JSONArray(prefs(c).getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val d = o.getJSONArray("days")
            Rule(
                o.getLong("id"),
                (0 until d.length()).map { i -> d.getInt(i) }.toSet(),
                o.getInt("h"), o.getInt("m"), o.getInt("p")
            )
        }
    }

    fun save(c: Context, rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach {
            arr.put(JSONObject().put("id", it.id).put("days", JSONArray(it.days.toList()))
                .put("h", it.hour).put("m", it.minute).put("p", it.percent))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }
}

object Scheduler {
    private const val EXTRA_TIME = "t"

    private fun nextTrigger(r: Rule, from: Calendar): Long? {
        for (offset in 0..7) {
            val cal = (from.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.get(Calendar.DAY_OF_WEEK) in r.days && cal.timeInMillis > from.timeInMillis)
                return cal.timeInMillis
        }
        return null
    }

    /** Schedules a single alarm for the soonest upcoming rule. Call after any change. */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val base = Intent(c, RuleReceiver::class.java)
        am.cancel(PendingIntent.getBroadcast(c, 0, base, PendingIntent.FLAG_IMMUTABLE))

        val now = Calendar.getInstance()
        val next = RuleStore.load(c).mapNotNull { nextTrigger(it, now) }.minOrNull() ?: return

        val pi = PendingIntent.getBroadcast(
            c, 0, base.putExtra(EXTRA_TIME, next),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** Apply every rule matching the fired time (last one wins on ties). */
    fun fire(c: Context, triggerMillis: Long) {
        val t = Calendar.getInstance().apply { timeInMillis = triggerMillis }
        RuleStore.load(c)
            .filter {
                t.get(Calendar.DAY_OF_WEEK) in it.days &&
                    t.get(Calendar.HOUR_OF_DAY) == it.hour && t.get(Calendar.MINUTE) == it.minute
            }
            .lastOrNull()?.let { applyVolume(c, it.percent) }
    }

    fun applyVolume(c: Context, percent: Int) {
        val audio = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        var v = (max * percent / 100.0).roundToInt()
        val dndOk = nm.isNotificationPolicyAccessGranted
        if (v == 0 && !dndOk) v = 1 // going fully silent needs Do Not Disturb access
        try {
            if (dndOk && v > 0 && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL)
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            audio.setStreamVolume(AudioManager.STREAM_RING, v, 0)
        } catch (e: SecurityException) {
        }
    }
}

class RuleReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.fire(c, i.getLongExtra("t", System.currentTimeMillis()))
        Scheduler.reschedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Scheduler.reschedule(c)
}
