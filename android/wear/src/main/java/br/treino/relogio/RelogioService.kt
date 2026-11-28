package br.treino.relogio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Serviço em primeiro plano: continua lendo o celular a cada 3 s e vibra no fim do descanso
 * mesmo com a tela do relógio apagada. Mostra o ícone de "atividade em andamento" no mostrador
 * (tocar nele volta para o app). Encerra sozinho depois de 10 min sem treino e sem a tela do app aberta.
 */
class RelogioService : Service() {

    companion object {
        private const val CANAL = "treino"
        private const val ID = 1
        fun iniciar(ctx: Context) { ctx.startForegroundService(Intent(ctx, RelogioService::class.java)) }
    }

    private val h = Handler(Looper.getMainLooper())
    private var trava: PowerManager.WakeLock? = null
    private var ultimoAtivo = 0L
    private var ultimaBusca = 0L
    private var iniciado = false

    private val tique = object : Runnable {
        override fun run() {
            val agora = System.currentTimeMillis()
            if (agora - ultimaBusca >= 3000) { ultimaBusca = agora; Sincronia.buscar() }
            when (Sincronia.alerta()) {
                Sincronia.Alerta.DESCANSO -> avisar(longArrayOf(0, 500, 200, 500, 200, 500), 3)
                Sincronia.Alerta.PARADO -> avisar(longArrayOf(0, 250, 150, 250, 150, 250, 150, 250), 2)
                Sincronia.Alerta.DEZ -> avisar(longArrayOf(0, 180), 0)
                Sincronia.Alerta.NENHUM -> {}
            }
            val treino = Sincronia.emTreino(Sincronia.atual())
            if (treino || Sincronia.visivel) ultimoAtivo = agora
            processadorLigado(treino)
            if (agora - ultimoAtivo > 10 * 60000L) { stopSelf(); return }
            h.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Sincronia.iniciar(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Treino em andamento", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sincronia com o celular e aviso do fim do descanso"
                setShowBadge(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!iniciado) {
            iniciado = true
            val abrir = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Treino")
                .setContentText("Sincronizando com o celular")
                .setContentIntent(abrir)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            // ícone no mostrador do relógio enquanto o serviço roda; tocar nele volta para o app
            OngoingActivity.Builder(applicationContext, ID, b)
                .setStaticIcon(R.drawable.ic_notif)
                .setTouchIntent(abrir)
                .setStatus(Status.Builder().addTemplate("Treino").build())
                .build()
                .apply(applicationContext)
            val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, ID, b.build(), tipo)
            ultimoAtivo = System.currentTimeMillis()
            h.post(tique)
        }
        return START_NOT_STICKY
    }

    /** Com treino aberto, o processador fica acordado com a tela apagada (a leitura de 3 s continua). */
    private fun processadorLigado(sim: Boolean) {
        if (sim && trava?.isHeld != true) {
            trava = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "treino:relogio")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        } else if (!sim && trava?.isHeld == true) {
            trava?.release(); trava = null
        }
    }

    private fun avisar(padrao: LongArray, bipes: Int) {
        try {
            val vib = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            val efeito = VibrationEffect.createWaveform(padrao, -1)
            // "alarme": vibra mesmo no modo não perturbe / hora de dormir
            vib.vibrate(efeito, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } catch (e: Exception) {}
        if (bipes > 0) try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            repeat(bipes) { i -> h.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180) }, i * 350L) }
            h.postDelayed({ tg.release() }, bipes * 350L + 400)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        h.removeCallbacks(tique)
        trava?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
