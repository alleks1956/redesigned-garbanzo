package com.example.tripodcamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_SHUTTER_DENOM = "shutter_denom"
        const val EXTRA_ISO = "iso"
        const val EXTRA_START_AT = "start_at"   // мс с 1970, 0 = сразу
        const val EXTRA_END_AT = "end_at"       // мс с 1970, 0 = до нажатия «Стоп»
        const val EXTRA_PASSWORD = "password"   // пароль для просмотра по Wi-Fi
    }

    private lateinit var intervalInput: EditText
    private lateinit var manualCheck: CheckBox
    private lateinit var shutterInput: EditText
    private lateinit var isoInput: EditText
    private lateinit var startTimeInput: EditText
    private lateinit var endTimeInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }
        fun numberField(default: String) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(default)
        }
        fun timeField() = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            hint = "ЧЧ:ММ, например 07:30"
        }

        root.addView(label("Интервал между кадрами, секунд:"))
        intervalInput = numberField("5")
        root.addView(intervalInput)

        root.addView(label("Выдержка не длиннее 1/N с (N = 500 значит 1/500):"))
        shutterInput = numberField("500")
        root.addView(shutterInput)

        manualCheck = CheckBox(this).apply {
            text = "Ручной режим (выдержка и ISO ровно как заданы; иначе авто с лимитом выдержки)"
            isChecked = false
        }
        root.addView(manualCheck)

        root.addView(label("ISO (только для ручного режима):"))
        isoInput = numberField("400")
        root.addView(isoInput)

        root.addView(label("Начать съёмку в (пусто = сразу после «Старт»):"))
        startTimeInput = timeField()
        root.addView(startTimeInput)

        root.addView(label("Закончить съёмку в (пусто = до нажатия «Стоп»):"))
        endTimeInput = timeField()
        root.addView(endTimeInput)

        root.addView(label("Пароль для просмотра по Wi-Fi (пусто = просмотр выключен):"))
        passwordInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("tripod", MODE_PRIVATE).getString("password", ""))
        }
        root.addView(passwordInput)

        root.addView(Button(this).apply {
            text = "Старт"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "Стоп"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TripodService::class.java))
                status.text = "Остановлено"
            }
        })

        status = TextView(this).apply {
            text = "Фото сохраняются в галерею: Pictures/TripodCamera"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun onStartClicked() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startShooting() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startShooting()
        } else {
            status.text = "Нужно разрешение на камеру"
        }
    }

    /** Разбирает «ЧЧ:ММ». Пусто = null (допустимо), неверный формат = исключение. */
    private fun parseTime(text: String): Pair<Int, Int>? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(s)
            ?: throw IllegalArgumentException("Время «$s» не похоже на ЧЧ:ММ")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) throw IllegalArgumentException("Время «$s» вне диапазона")
        return h to min
    }

    /** Ближайший момент времени ЧЧ:ММ строго после moment. */
    private fun nextOccurrence(h: Int, m: Int, after: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= after) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun startShooting() {
        val interval = intervalInput.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 5L
        val manual = manualCheck.isChecked
        val denom = shutterInput.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: 500
        val iso = isoInput.text.toString().toIntOrNull()?.coerceAtLeast(50) ?: 400
        val password = passwordInput.text.toString()
        getSharedPreferences("tripod", MODE_PRIVATE).edit().putString("password", password).apply()

        val now = System.currentTimeMillis()
        var startAt = 0L
        var endAt = 0L
        try {
            parseTime(startTimeInput.text.toString())?.let { (h, m) -> startAt = nextOccurrence(h, m, now) }
            parseTime(endTimeInput.text.toString())?.let { (h, m) ->
                endAt = nextOccurrence(h, m, if (startAt > 0L) startAt else now)
            }
        } catch (e: IllegalArgumentException) {
            status.text = e.message
            return
        }

        startForegroundService(
            Intent(this, TripodService::class.java)
                .putExtra(TripodService.EXTRA_INTERVAL, interval)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra(EXTRA_SHUTTER_DENOM, denom)
                .putExtra(EXTRA_ISO, iso)
                .putExtra(EXTRA_START_AT, startAt)
                .putExtra(EXTRA_END_AT, endAt)
                .putExtra(EXTRA_PASSWORD, password)
        )

        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val mode = if (manual) "ручной, 1/$denom с, ISO $iso" else "авто, выдержка не длиннее 1/$denom с"
        val startText = if (startAt > 0L) "старт ${fmt.format(Date(startAt))}" else "старт сразу"
        val endText = if (endAt > 0L) "конец ${fmt.format(Date(endAt))}" else "конец: по кнопке «Стоп»"
        val view = when {
            password.isEmpty() -> "\nПросмотр по Wi-Fi выключен: задайте пароль"
            else -> FrameServer.localAddress()
                ?.let { "\nПросмотр: http://$it:${FrameServer.PORT} (пароль из поля выше, имя любое)" }
                ?: "\nПросмотр: нет Wi-Fi"
        }
        status.text = "val view = FrameServer.localAddress()
    ?.let { "\nПросмотр: http://$it:${FrameServer.PORT}" } ?: "\nПросмотр: нет Wi-Fi"
status.text = "Запущено: $startText, $endText\nИнтервал $interval с\nЭкспозиция: $mode\nФото: Pictures/TripodCamera$view""
    }
}
