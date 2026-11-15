package com.osamaalek.kiosklauncher.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.osamaalek.kiosklauncher.R
import com.osamaalek.kiosklauncher.ui.MainActivity

class KioskWatchdogService : Service() {

    private val TAG = "KioskWatchdog"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 1000L // 1 segundo
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "KioskWatchdogChannel"

    // Variável para evitar loops infinitos caso precisemos abrir algo intencionalmente
    companion object {
        var isKioskTemporarilyDisabled = false
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Watchdog Service Iniciado")
        isKioskTemporarilyDisabled = false

        // 0. Torna este serviço um Foreground Service para não ser morto pelo Android
        startForegroundService()
        
        // 1. Cria a sobreposição invisível para bloquear a barra de notificações
        setupStatusBarOverlay()
        
        // 2. Inicia o monitoramento de evasão
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, checkInterval)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Watchdog Service onStartCommand")
        isKioskTemporarilyDisabled = false
        if (overlayView == null) {
            setupStatusBarOverlay()
        }
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, checkInterval)
        return START_STICKY
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serviço de Kiosk",
                NotificationManager.IMPORTANCE_LOW // Low para não fazer barulho/vibrar
            ).apply {
                description = "Mantém o Kiosk em execução"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monitoramento Kiosk")
            .setContentText("O tablet está bloqueado")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // Ícone genérico do sistema
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Watchdog Service Destruído")
        handler.removeCallbacks(watchdogRunnable)
        removeStatusBarOverlay()
    }

    /**
     * ESTRATÉGIA 3 (Fully Kiosk): 
     * Desenha um retângulo transparente no topo da tela, bem onde fica a barra de status.
     * Isso impede fisicamente que o usuário consiga arrastar o dedo de cima para baixo.
     */
    private fun setupStatusBarOverlay() {
        try {
            if (overlayView != null) return
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            overlayView = View(this).apply {
                // Deixa invisível, mas consome os toques
                setBackgroundColor(0x00000000)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                100, // Altura estimada da barra de status
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Sobreposição da barra de status criada com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao criar sobreposição (Permissão concedida?): ${e.message}")
        }
    }

    private fun removeStatusBarOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao remover sobreposição: ${e.message}")
        }
    }

    private var lastRedirectTime = 0L

    /**
     * ESTRATÉGIA 5 (Fully Kiosk):
     * Verifica constantemente qual é o pacote que está no topo da tela.
     * Se for diferente do nosso (E não estivermos pausados para atualização), ele reabre nosso app.
     */
    private fun checkForegroundApp() {
        if (isKioskTemporarilyDisabled) return
        
        // Se o nosso app JÁ ESTÁ na tela (foreground), ignoramos o delay do UsageStatsManager
        // e não tentamos redirecionar de volta.
        if (MainActivity.isAppInForeground) return

        // Cooldown de 5 segundos após um redirecionamento para evitar loops enquanto o UsageStats se atualiza
        val now = System.currentTimeMillis()
        if (now - lastRedirectTime < 5000) return

        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000 // Últimos 10 segundos

        val usageStatsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        
        var topPackageName = ""
        var lastTimeUsed = 0L

        if (!usageStatsList.isNullOrEmpty()) {
            for (usageStats in usageStatsList) {
                if (usageStats.lastTimeUsed > lastTimeUsed) {
                    topPackageName = usageStats.packageName
                    lastTimeUsed = usageStats.lastTimeUsed
                }
            }
        }

        if (topPackageName.isNotEmpty() && topPackageName != packageName) {
            Log.w(TAG, "Fuga detectada! O pacote $topPackageName está na frente. Forçando retorno.")
            
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            lastRedirectTime = System.currentTimeMillis()
        }
    }
}
