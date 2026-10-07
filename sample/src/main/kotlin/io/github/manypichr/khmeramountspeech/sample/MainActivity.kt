package io.github.manypichr.khmeramountspeech.sample

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.manypichr.khmeramountspeech.AmountAudioExporter
import io.github.manypichr.khmeramountspeech.AmountException
import io.github.manypichr.khmeramountspeech.AmountSpeech
import io.github.manypichr.khmeramountspeech.CurrencyCode
import io.github.manypichr.khmeramountspeech.KhmerAmountSpeaker
import io.github.manypichr.khmeramountspeech.SpeechLanguage
import io.github.manypichr.khmeramountspeech.VoiceType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Demo: type an amount, pick currency / language / voice type, then speak it or export a WAV. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var speaker: KhmerAmountSpeaker
    private lateinit var exporter: AmountAudioExporter

    private var currency = CurrencyCode.KHR
    private var language = SpeechLanguage.KHMER
    private var voiceType = VoiceType.AMOUNT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = KhmerAmountSpeaker(this)
        exporter = AmountAudioExporter(this)
        scope.launch { speaker.preload() }

        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
        }
        val input = EditText(this).apply {
            hint = "Amount"
            setText("1,250,000")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val preview = TextView(this).apply { textSize = 20f; setPadding(0, padding, 0, padding) }
        val status = TextView(this)

        fun refresh() {
            preview.text = try {
                AmountSpeech(input.text.toString(), currency, language, voiceType).text
            } catch (e: AmountException) {
                e.message
            }
        }

        fun <T> choices(values: List<Pair<String, T>>, initial: T, onPick: (T) -> Unit) = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            values.forEach { (label, value) ->
                val button = RadioButton(context).apply { text = label; id = android.view.View.generateViewId() }
                addView(button)
                if (value == initial) check(button.id)
                button.setOnClickListener { onPick(value); refresh() }
            }
        }

        root.addView(input, MATCH_PARENT, WRAP_CONTENT)
        root.addView(choices(listOf("KHR" to CurrencyCode.KHR, "USD" to CurrencyCode.USD), currency) { currency = it })
        root.addView(choices(listOf("ខ្មែរ" to SpeechLanguage.KHMER, "English" to SpeechLanguage.ENGLISH), language) { language = it })
        root.addView(
            choices(
                listOf("Amount" to VoiceType.AMOUNT, "Confirm" to VoiceType.CONFIRM_PAY, "Received" to VoiceType.RECEIVED),
                voiceType,
            ) { voiceType = it },
        )
        root.addView(preview)
        root.addView(Button(this).apply {
            text = "Speak"
            setOnClickListener {
                scope.launch {
                    try {
                        speaker.speak(input.text.toString(), currency, language, voiceType)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        root.addView(Button(this).apply { text = "Stop"; setOnClickListener { speaker.stop() } })
        root.addView(Button(this).apply {
            text = "Export WAV"
            setOnClickListener {
                scope.launch {
                    status.text = try {
                        exporter.speechWav(input.text.toString(), currency, language, voiceType).absolutePath
                    } catch (e: Exception) {
                        e.message
                    }
                }
            }
        })
        root.addView(status)
        scope.launch { speaker.state.collect { title = "Khmer Amount Speech · ${it.name.lowercase()}" } }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        refresh()
        setContentView(root)
    }

    override fun onDestroy() {
        speaker.stop()
        scope.cancel()
        super.onDestroy()
    }
}
