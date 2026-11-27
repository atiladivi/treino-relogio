package br.treino.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject

/**
 * Serviço em primeiro plano enquanto há treino rodando: o Android não congela o app com a tela apagada
 * (a página continua lendo os toques do relógio e contando o tempo). Mostra a notificação fixa com o
 * exercício, o cronômetro e o botão da série.
 */
class TreinoService : Service() {

    companion object {
        private const val CANAL = "treino"
        private const val ID = 1
        @Volatile private var ultimo: String = ""

        fun atualizar(ctx: Context, o: JSONObject) {
            val j = o.toString()
            if (j == ultimo) return
            ultimo = j
            ctx.startForegroundService(Intent(ctx, TreinoService::class.java).putExtra("estado", j))
        }

        fun parar(ctx: Context) {
            if (ultimo.isEmpty()) return
            ultimo = ""
            ctx.stopService(Intent(ctx, TreinoService::class.java))
        }
    }

    private var trava: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Treino em andamento", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Cronômetro do treino e botão da série"
                setShowBadge(false)
            })
        // mantém o processador acordado com a tela apagada (a sincronia com o relógio roda a cada 3 s)
        trava = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "treino:sincronia")
            .apply { acquire(3 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val o = try { JSONObject(intent?.getStringExtra("estado") ?: "{}") } catch (e: Exception) { JSONObject() }
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, notificacao(o), tipo)
        return START_NOT_STICKY
    }

    private fun notificacao(o: JSONObject): Notification {
        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val abrir = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsPi)
        val pausado = o.optBoolean("pausado")
        val desde = o.optLong("desde", 0L)
        val b = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(o.optString("titulo", "Treino"))
            .setContentText(o.optString("texto", ""))
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // aparece na tela de bloqueio
        if (desde > 0 && !pausado) b.setWhen(desde).setShowWhen(true).setUsesChronometer(true)
        val acao = o.optString("acao", "")
        if (acao.isNotEmpty() && !pausado) {
            val pi = PendingIntent.getBroadcast(this, 1, Intent(this, AcaoReceiver::class.java), flagsPi)
            b.addAction(0, acao, pi)
        }
        return b.build()
    }

    override fun onDestroy() {
        trava?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
