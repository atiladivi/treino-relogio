package br.treino.relogio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Tela do relógio: a mesma da página (rótulo, exercício, anel com o tempo, botão grande).
 * A chave da ponte chega uma vez pelo PC:
 *   adb shell am start -n br.treino.relogio/.MainActivity --es chave <CHAVE>
 */
class MainActivity : Activity() {

    private lateinit var tela: Tela
    private val h = Handler(Looper.getMainLooper())
    private var telaPresa = false

    private val tique = object : Runnable {
        override fun run() {
            val treino = Sincronia.emTreino(Sincronia.atual())
            if (treino != telaPresa) { // com treino aberto a tela não apaga (como na página)
                telaPresa = treino
                if (treino) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            tela.telaPresa = telaPresa
            tela.invalidate()
            h.postDelayed(this, 250)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Sincronia.iniciar(this)
        lerChave(intent)
        tela = Tela(this) { if (Sincronia.tocar()) { tela.performHapticFeedback(HapticFeedbackConstants.CONFIRM); tela.invalidate() } }
        setContentView(tela)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        lerChave(i)
    }

    private fun lerChave(i: Intent?) {
        val k = i?.getStringExtra("chave") ?: return
        if (k.isNotBlank()) Sincronia.guardarChave(this, k)
    }

    override fun onStart() {
        super.onStart()
        Sincronia.visivel = true
        RelogioService.iniciar(this)
        Sincronia.buscar()
        h.post(tique)
    }

    override fun onStop() {
        Sincronia.visivel = false
        h.removeCallbacks(tique)
        super.onStop()
    }
}

/** Desenha tudo dentro do círculo da tela (medidas em frações do lado do quadrado inscrito). */
class Tela(ctx: Context, private val aoTocar: () -> Unit) : View(ctx) {

    var telaPresa = false

    private val AZUL = Color.parseColor("#4f8ff7")
    private val VERDE = Color.parseColor("#34c27a")
    private val VERMELHO = Color.parseColor("#f05a4f")
    private val AMARELO = Color.parseColor("#f2c14e")
    private val TINTA = Color.parseColor("#f2f4f7")
    private val TINTA2 = Color.parseColor("#9aa4b5")
    private val LINHA = Color.parseColor("#262b34")

    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val forte = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val anel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val botao = RectF()
    private var temBotao = false

    private fun escrever(c: Canvas, p: TextPaint, s: String, x: Float, y: Float, tam: Float, cor: Int, larg: Float) {
        p.textSize = tam; p.color = cor
        c.drawText(TextUtils.ellipsize(s, p, larg, TextUtils.TruncateAt.END).toString(), x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val agora = System.currentTimeMillis()
        // fundo: pisca em vermelho escuro duas vezes no fim do descanso / celular parado
        val df = agora - Sincronia.flashDesde
        var fundo = Color.BLACK
        if (df in 0..2399) {
            val f = (df % 1200) / 1200f
            val k = if (f < .3f) f / .3f else (1 - f) / .7f
            fundo = Color.rgb((0x3a * k).toInt(), (0x12 * k).toInt(), (0x10 * k).toInt())
        }
        c.drawColor(fundo)

        val v = Sincronia.visual(telaPresa)
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2; val oy = (height - s) / 2
        val cx = width / 2f
        fun y(f: Float) = oy + f * s

        escrever(c, txt, v.rot.uppercase(), cx, y(.155f), .055f * s, if (v.rotVerde) VERDE else AZUL, .62f * s)
        escrever(c, txt, v.nome, cx, y(.235f), .068f * s, TINTA, .78f * s)

        // anel
        val cy = y(.5f); val r = .22f * s
        anel.strokeWidth = .0375f * s
        anel.color = LINHA
        c.drawCircle(cx, cy, r, anel)
        if (v.corArco != 0 && v.arco > 0f) {
            anel.color = when (v.corArco) { 1 -> AZUL; 3 -> VERMELHO; else -> VERDE }
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * v.arco, false, anel)
        }
        escrever(c, forte, v.t, cx, cy + .055f * s, .15f * s, TINTA, .4f * s)
        if (v.alvo.isNotEmpty()) escrever(c, txt, v.alvo, cx, cy + .15f * s, .046f * s, TINTA2, .3f * s)

        // linha de cima do anel: ponto de conexão + informação
        txt.textSize = .042f * s
        val info = TextUtils.ellipsize(v.info, txt, .36f * s, TextUtils.TruncateAt.END).toString()
        val li = txt.measureText(info); val pr = .012f * s
        val iy = cy - .115f * s
        pinta.color = when (v.ponto) { 1 -> VERDE; 2 -> VERMELHO; 3 -> AMARELO; else -> LINHA }
        val x0 = cx - (li + (if (info.isEmpty()) 0f else pr * 2 + .012f * s)) / 2
        c.drawCircle(x0 + pr, iy - pr * .9f, pr, pinta)
        if (info.isNotEmpty()) { txt.color = TINTA2; c.drawText(info, x0 + pr * 2 + .012f * s + li / 2, iy, txt) }

        // botão
        temBotao = v.botao.isNotEmpty()
        botao.set(cx - .29f * s, y(.765f), cx + .29f * s, y(.895f))
        if (temBotao) {
            pinta.color = if (v.botaoVerde) VERDE else AZUL
            c.drawRoundRect(botao, botao.height() / 2, botao.height() / 2, pinta)
            escrever(c, forte, v.botao, cx, botao.centerY() + .024f * s, .068f * s,
                if (v.botaoVerde) Color.parseColor("#04150c") else Color.WHITE, botao.width() * .9f)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && temBotao) {
            val folga = .04f * minOf(width, height)
            if (e.x in botao.left - folga..botao.right + folga && e.y in botao.top - folga..botao.bottom + folga) aoTocar()
        }
        return true
    }
}
