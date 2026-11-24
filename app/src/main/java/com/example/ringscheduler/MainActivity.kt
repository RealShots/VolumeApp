package com.example.ringscheduler

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(Button(this).apply {
            text = "1. Allow Do Not Disturb access (for silent)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        })
        if (Build.VERSION.SDK_INT >= 31) {
            root.addView(Button(this).apply {
                text = "2. Allow exact alarms (for on-time changes)"
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
            })
        }
        root.addView(Button(this).apply {
            text = "+ Add rule"
            setOnClickListener { addRule() }
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Scheduler.reschedule(this)
    }

    private fun refresh() {
        list.removeAllViews()
        RuleStore.load(this).forEach { rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = rule.label()
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    RuleStore.save(this@MainActivity, RuleStore.load(this@MainActivity).filter { it.id != rule.id })
                    refresh(); Scheduler.reschedule(this@MainActivity)
                }
            })
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun addRule() {
        val names = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val checked = BooleanArray(7) { it in 1..5 } // default Mon–Fri
        AlertDialog.Builder(this)
            .setTitle("Days")
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Next") { _, _ ->
                val days = (0..6).filter { checked[it] }.map { it + 1 }.toSet() // Calendar.SUNDAY = 1
                if (days.isNotEmpty()) {
                    TimePickerDialog(this, { _, h, m -> pickVolume(days, h, m) }, 9, 0, true).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickVolume(days: Set<Int>, hour: Int, minute: Int) {
        val label = TextView(this).apply { text = "Ring volume: 50%"; textSize = 18f }
        val bar = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { label.text = "Ring volume: $p%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(label); addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle("Volume")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val rules = RuleStore.load(this) +
                    Rule(System.currentTimeMillis(), days, hour, minute, bar.progress)
                RuleStore.save(this, rules)
                refresh(); Scheduler.reschedule(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
