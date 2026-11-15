package com.osamaalek.kiosklauncher.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.DigitsKeyListener
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.osamaalek.kiosklauncher.BuildConfig
import com.osamaalek.kiosklauncher.R
import com.osamaalek.kiosklauncher.services.KioskWatchdogService
import com.osamaalek.kiosklauncher.util.KioskUtil

/**
 * Fragmento principal do modo Kiosk, configurado para carregar e manter travada
 * a página do Totem NPS com o cabeçalho seguro X-Nps-Kiosk e suporte offline.
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "KioskWebView"
        private const val TOTEM_URL = "https://portal.grupodem.com.br/nps/totem"
        private const val ALLOWED_HOST = "portal.grupodem.com.br"
        private const val ALLOWED_SCHEME = "https"
        private const val KIOSK_HEADER_NAME = "X-Nps-Kiosk"
        private const val RETRY_INTERVAL_MS = 30_000L
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var layoutError: View

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isOffline = false
    private var hasMainFrameError = false
    private var isFirstResume = true

    // Controle de rolagem para sincronização de containers internos (overflow CSS)
    @Volatile
    private var canDomScrollUp = false
    private var lockSwipeDuringCurrentTouch = false
    private var touchStartY = 0f

    // Handler para retentativas automáticas a cada 30 segundos em caso de erro
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        if (isAdded && hasMainFrameError) {
            Log.d(TAG, "Tentando reconectar página do Totem (intervalo de 30s)...")
            loadTotemUrl()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        layoutError = view.findViewById(R.id.layoutError)
        val btnExit: ImageButton = view.findViewById(R.id.btnExit)

        // Exige um puxão mais intencional para atualizar
        swipeRefreshLayout.setDistanceToTriggerSync(320)

        // Só permite que o SwipeRefreshLayout intercepte se WebView e DOM estiverem no topo
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            val isNotTopEdgeTouch = touchStartY > 350f
            webView.canScrollVertically(-1) || canDomScrollUp || lockSwipeDuringCurrentTouch || isNotTopEdgeTouch
        }

        // Puxar para baixo recarrega via loadUrl com o cabeçalho seguro (nunca reload())
        swipeRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "Usuário puxou a tela no topo. Recarregando totem com cabeçalho...")
            loadTotemUrl()
        }

        btnExit.setOnClickListener {
            showPasswordDialog()
        }

        setupWebView()
        setupNetworkMonitoring()

        // Carregamento inicial do totem com cabeçalho secreto
        loadTotemUrl()
    }

    override fun onResume() {
        super.onResume()
        // Persiste cookies na volta ao app
        CookieManager.getInstance().flush()

        // Na volta para o app (após saída temporária), recarrega a página garantindo o cabeçalho
        if (!isFirstResume) {
            loadTotemUrl()
        }
        isFirstResume = false
    }

    override fun onPause() {
        super.onPause()
        // Persiste cookies ao pausar o app para sobreviver a reinícios
        CookieManager.getInstance().flush()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelRetry()
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
    }

    /**
     * Carrega a URL do Totem sempre enviando o cabeçalho secreto X-Nps-Kiosk.
     * Valida estritamente se o host é exatamente portal.grupodem.com.br e o esquema é https.
     */
    private fun loadTotemUrl(targetUrl: String = TOTEM_URL) {
        val uri = Uri.parse(targetUrl)
        val isHttps = uri.scheme.equals(ALLOWED_SCHEME, ignoreCase = true)
        val isAllowedHost = uri.host.equals(ALLOWED_HOST, ignoreCase = true)

        if (isHttps && isAllowedHost) {
            Log.d(TAG, "Carregando URL do Totem")
            val headers = mapOf(KIOSK_HEADER_NAME to BuildConfig.NPS_KIOSK_HEADER)
            webView.loadUrl(targetUrl, headers)
        } else {
            Log.w(TAG, "Tentativa de envio de cabeçalho bloqueada para host/esquema não autorizado.")
        }
    }

    private fun showErrorScreen() {
        activity?.runOnUiThread {
            layoutError.visibility = View.VISIBLE
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun hideErrorScreen() {
        activity?.runOnUiThread {
            layoutError.visibility = View.GONE
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun scheduleRetry() {
        retryHandler.removeCallbacks(retryRunnable)
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    private fun cancelRetry() {
        retryHandler.removeCallbacks(retryRunnable)
    }

    private fun showPasswordDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_admin_options, null)
        val tilPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPassword)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)
        val btnCleanCache = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCleanCache)
        val btnExitKiosk = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExitKiosk)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        etPassword.keyListener = DigitsKeyListener.getInstance("0123456789")

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            etPassword.requestFocus()
            etPassword.postDelayed({
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etPassword, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }

        btnCleanCache.setOnClickListener {
            val password = etPassword.text?.toString()?.trim().orEmpty()
            if (password == "0250") {
                dialog.dismiss()
                clearAppCache()
            } else {
                tilPassword.error = "Senha incorreta"
                Toast.makeText(requireContext(), "Senha incorreta", Toast.LENGTH_SHORT).show()
            }
        }

        btnExitKiosk.setOnClickListener {
            val password = etPassword.text?.toString()?.trim().orEmpty()
            if (password == "0250") {
                dialog.dismiss()
                exitKioskMode()
            } else {
                tilPassword.error = "Senha incorreta"
                Toast.makeText(requireContext(), "Senha incorreta", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun exitKioskMode() {
        KioskWatchdogService.isKioskTemporarilyDisabled = true
        val serviceIntent = Intent(requireContext(), KioskWatchdogService::class.java)
        requireContext().stopService(serviceIntent)

        KioskUtil.stopKioskMode(requireActivity())

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        requireActivity().finish()
    }

    /**
     * Limpeza manual de cache exclusiva do administrador (nunca executada automaticamente).
     */
    private fun clearAppCache() {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            WebStorage.getInstance().deleteAllData()

            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()

            requireContext().cacheDir.deleteRecursively()
            requireContext().externalCacheDir?.deleteRecursively()

            Toast.makeText(requireContext(), "Cache limpo com sucesso!", Toast.LENGTH_SHORT).show()

            // Recarrega o Totem sempre com o cabeçalho secreto
            loadTotemUrl()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar cache: ${e.message}", e)
            Toast.makeText(requireContext(), "Erro ao limpar cache: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Conexão de rede disponível.")
                if (isOffline || hasMainFrameError) {
                    isOffline = false
                    Log.d(TAG, "Internet retornou. Recarregando página...")
                    cancelRetry()
                    activity?.runOnUiThread {
                        loadTotemUrl()
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Conexão de rede perdida.")
                isOffline = true
            }
        }

        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)

        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        isOffline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = webView.settings

        // 1. Configurações essenciais para web apps (DOM storage guarda votos offline)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 2. Otimizações de exibição
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // 3. Segurança TLS e Conteúdo Misto estrito (nunca permitir misto)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(true)

        // 4. Debugging desativado no build de release
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        // 5. Configuração e persistência de Cookies (HttpOnly nps_kiosk e nps_totem)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // NÃO limpar cache, cookies nem localStorage aqui no fluxo automático!

        // Ponte JS para sincronizar scroll com CSS overflow interno
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setCanScrollUp(value: Boolean) {
                canDomScrollUp = value
                webView.post {
                    swipeRefreshLayout.isEnabled = !value
                    if (value) {
                        lockSwipeDuringCurrentTouch = true
                        webView.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
        }, "KioskScrollBridge")

        // Evita que SwipeRefreshLayout intercepte toques durante navegação no corpo da página
        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    val isNotTopEdgeTouch = touchStartY > 350f
                    val canScrollUp = webView.canScrollVertically(-1) || canDomScrollUp || isNotTopEdgeTouch
                    lockSwipeDuringCurrentTouch = canScrollUp
                    if (lockSwipeDuringCurrentTouch) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - touchStartY
                    val isNotTopEdgeTouch = touchStartY > 350f
                    val canScrollUp = webView.canScrollVertically(-1) || canDomScrollUp || isNotTopEdgeTouch
                    if (dy < -12f || canScrollUp) {
                        lockSwipeDuringCurrentTouch = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lockSwipeDuringCurrentTouch = false
                }
            }
            false
        }

        // Cliente WebView com controle de navegação, TLS e tratamento de erros do documento
        webView.webViewClient = KioskWebViewClient(
            onPageReady = { view ->
                swipeRefreshLayout.isRefreshing = false
                injectScrollDetector(view)
            },
            onMainFrameError = {
                hasMainFrameError = true
                showErrorScreen()
                scheduleRetry()
            },
            onPageSuccess = {
                hasMainFrameError = false
                hideErrorScreen()
                cancelRetry()
            }
        )

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
    }

    private fun injectScrollDetector(view: WebView) {
        val js = """
            (function() {
                if (window.__kioskScrollTrackerInstalled) return;
                window.__kioskScrollTrackerInstalled = true;

                var lastReported = null;

                function checkDoc(doc, win) {
                    if (!doc) return false;
                    var y = (win && (win.scrollY || win.pageYOffset)) || (doc.documentElement ? doc.documentElement.scrollTop : 0) || (doc.body ? doc.body.scrollTop : 0) || 0;
                    if (y > 2) return true;

                    var els = doc.querySelectorAll('div, main, section, article, form, table, [role="main"]');
                    for (var i = 0; i < els.length; i++) {
                        if (els[i].scrollTop > 2) return true;
                    }

                    var iframes = doc.querySelectorAll('iframe');
                    for (var j = 0; j < iframes.length; j++) {
                        try {
                            var ifr = iframes[j];
                            var subDoc = ifr.contentDocument || (ifr.contentWindow && ifr.contentWindow.document);
                            if (subDoc && checkDoc(subDoc, ifr.contentWindow)) {
                                return true;
                            }
                        } catch (e) {}
                    }
                    return false;
                }

                function notify() {
                    try {
                        var scrolled = checkDoc(document, window);
                        if (scrolled !== lastReported) {
                            lastReported = scrolled;
                            if (window.KioskScrollBridge && window.KioskScrollBridge.setCanScrollUp) {
                                window.KioskScrollBridge.setCanScrollUp(scrolled);
                            }
                        }
                    } catch (e) {}
                }

                function attachListeners(win) {
                    if (!win) return;
                    try {
                        win.addEventListener('scroll', notify, true);
                        win.addEventListener('touchstart', notify, { passive: true, capture: true });
                        win.addEventListener('touchmove', notify, { passive: true, capture: true });
                        win.addEventListener('touchend', notify, { passive: true, capture: true });
                    } catch (e) {}
                }

                attachListeners(window);

                setInterval(function() {
                    try {
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            if (!iframes[i].__kioskListenerAttached && iframes[i].contentWindow) {
                                iframes[i].__kioskListenerAttached = true;
                                attachListeners(iframes[i].contentWindow);
                            }
                        }
                    } catch (e) {}
                    notify();
                }, 250);

                notify();
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Cliente WebView customizado para restringir navegação, validar TLS e gerenciar erros.
     */
    private class KioskWebViewClient(
        private val onPageReady: (WebView) -> Unit,
        private val onMainFrameError: () -> Unit,
        private val onPageSuccess: () -> Unit
    ) : WebViewClient() {

        private val clientTag = "KioskWebViewClient"
        private var isCurrentLoadFailed = false

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isCurrentLoadFailed = false
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            super.onPageCommitVisible(view, url)
            if (!isCurrentLoadFailed) {
                onPageReady(view)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Persiste cookies imediatamente após o carregamento da página
            CookieManager.getInstance().flush()

            if (!isCurrentLoadFailed) {
                onPageSuccess()
                onPageReady(view)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleUrlLoading(request.url.toString())
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return handleUrlLoading(url)
        }

        private fun handleUrlLoading(url: String): Boolean {
            val uri = Uri.parse(url)
            val isHttps = uri.scheme.equals(ALLOWED_SCHEME, ignoreCase = true)
            val isAllowedHost = uri.host.equals(ALLOWED_HOST, ignoreCase = true)
            val path = uri.path.orEmpty()
            val isNpsPath = path == "/nps" || path.startsWith("/nps/")

            if (isHttps && isAllowedHost && isNpsPath) {
                // Permite navegação apenas dentro de https://portal.grupodem.com.br/nps/...
                return false
            }

            Log.w(clientTag, "Navegação bloqueada para URL fora do escopo permitido: $url")
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            Log.e(clientTag, "Erro SSL no carregamento. Cancelando requisição.")
            // SEMPRE cancela o carregamento em caso de erro SSL. Nunca chamar proceed().
            handler.cancel()
            isCurrentLoadFailed = true
            onMainFrameError()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                Log.e(clientTag, "Falha no documento principal (código: ${error.errorCode})")
                isCurrentLoadFailed = true
                onMainFrameError()
            }
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (failingUrl.startsWith("https://$ALLOWED_HOST/nps")) {
                Log.e(clientTag, "Falha no documento principal (legado, código: $errorCode)")
                isCurrentLoadFailed = true
                onMainFrameError()
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request.isForMainFrame) {
                val statusCode = errorResponse.statusCode
                Log.e(clientTag, "Erro HTTP no documento principal: $statusCode")
                if (statusCode == 404 || statusCode >= 500) {
                    isCurrentLoadFailed = true
                    onMainFrameError()
                }
            }
        }
    }
}
