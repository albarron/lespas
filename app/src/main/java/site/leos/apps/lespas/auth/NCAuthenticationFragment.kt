package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import java.net.URL

class NCAuthenticationFragment: Fragment() {
    private lateinit var authWebpage: WebView
    private lateinit var authWebpageBG: ViewGroup
    private var sloganView: TextView? = null

    private var reLogin: Boolean = false
    private lateinit var theming: NCLoginFragment.AuthenticateViewModel.NCThemimg

    private val authenticateModel: NCLoginFragment.AuthenticateViewModel by activityViewModels()

    private val scanIntent = Intent("com.google.zxing.client.android.SCAN")
    private var scanRequestLauncher: ActivityResultLauncher<Intent>? = null

    private var actionBarHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reLogin = requireArguments().getBoolean(KEY_RELOGIN, false)
        theming = requireArguments().getParcelable(KEY_THEMING) ?: NCLoginFragment.AuthenticateViewModel.NCThemimg()

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authWebpage.canGoBack()) authWebpage.goBack() else parentFragmentManager.popBackStack()
            }
        })

        if (reLogin) {
            setHasOptionsMenu(true)
            scanRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringExtra("SCAN_RESULT")?.let { scanResult ->
                        ("nc://login/user:(.*)&password:(.*)&server:(.*)").toRegex().matchEntire(scanResult)?.destructured?.let { (username, token, server) ->
                            prepareCredential(server, username, token)
                        }
                    }
                }

                // TODO Show scan error
            }
        }

        actionBarHeight = savedInstanceState?.getInt(KEY_ACTION_BAR_HEIGHT) ?: (requireActivity() as AppCompatActivity).supportActionBar?.height ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_nc_authentication, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set content below action toolbar if launch from Setting
        if (reLogin) view.setPadding(view.paddingLeft, actionBarHeight, view.paddingRight, 0)

        authWebpageBG = view.findViewById(R.id.webview_background)
        authWebpage = view.findViewById<WebView>(R.id.webview).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.apply {
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) {
                            // Detected Nextcloud server authentication return special uri scheme: "nc://login/server:<server>&user:<loginname>&password:<password>"
                            ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(this.path.toString())?.destructured?.let { (server, username, token) ->
                                prepareCredential(server, username, token)
                            } ?: run {
                                // Can't parse Nextcloud server's return
                                if (reLogin) {
                                    // TODO prompt user of failure
                                } else authenticateModel.setAuthResult(NCLoginFragment.AuthenticateViewModel.RESULT_FAIL)

                                parentFragmentManager.popBackStack()
                            }

                            // Don't load this uri with webview
                            return true
                        }
                    }

                    // Continue loading in webview
                    return false
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (errorResponse != null) view?.reload()   // TODO: better error handling
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onPageFinished(webView: WebView?, url: String?) {
                    super.onPageFinished(webView, url)

                    webView?.let {
                        if (webView.alpha == 0f) {
                            authWebpageBG.background = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.color_background))
                            sloganView?.clearAnimation()

                            authWebpage.apply {
                                alpha = 0f
                                animate().alpha(1f).setDuration(resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()).setListener(object: AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator?) {
                                        requestFocus()
                                        super.onAnimationEnd(animation)
                                    }
                                })
                            }
                        }
                    }
                }

                // Have to allow self-signed certificate
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    if (error?.primaryError == SslError.SSL_IDMISMATCH && authenticateModel.getAccount().selfSigned) handler?.proceed() else handler?.cancel()
                }
            }

            settings.apply {
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "${resources.getString(R.string.app_name)} on ${Tools.getDeviceModel()}"
                javaScriptEnabled = true
            }

            savedInstanceState ?: run {
                CookieManager.getInstance().removeAllCookies(null)
                clearCache(true)
            }
        }

        savedInstanceState?.let {
            authWebpage.restoreState(it)
        } ?: run {
            // Show a loading sign first
            authWebpage.alpha = 0f

            if (theming.color != Color.TRANSPARENT) view.findViewById<FrameLayout>(R.id.theme_background).background = ColorDrawable(theming.color)
            if (theming.slogan.isNotEmpty()) {
                sloganView = view.findViewById<TextView>(R.id.slogan).apply {
                    text = theming.slogan
                    setTextColor(theming.textColor)

                    alpha = 0.2f
                    ObjectAnimator.ofFloat(this, "alpha", 1f).run {
                        duration = 800
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            } else {
                authWebpageBG.background = (ContextCompat.getDrawable(requireContext(), R.drawable.animated_placeholder) as AnimatedVectorDrawable).apply {
                    if (theming.color != Color.TRANSPARENT) {
                        setTintList(ColorStateList.valueOf(theming.color))
                        setTintMode(PorterDuff.Mode.ADD)
                    }
                    start()
                }
            }

            authWebpage.loadUrl("${authenticateModel.getAccount().serverUrl}${LOGIN_FLOW_ENDPOINT}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                CONFIRM_NEW_ACCOUNT_DIALOG -> {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                        requireActivity().packageManager.setComponentEnabledSetting(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                        requireActivity().finish()
                        // TODO allow user re-login to a different account
                    } else parentFragmentManager.popBackStack()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (theming.color != Color.TRANSPARENT) requireActivity().window.statusBarColor = theming.color
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        authWebpage.saveState(outState)
        outState.putInt(KEY_ACTION_BAR_HEIGHT, actionBarHeight)
    }

    override fun onDestroyView() {
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.color_primary)
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        if (reLogin) inflater.inflate(R.menu.authentication_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        if (reLogin) {
            (scanIntent.resolveActivity(requireContext().packageManager) != null).let { scannerAvailable ->
                menu.findItem(R.id.option_menu_qr_scanner)?.run {
                    isEnabled = scannerAvailable
                    isVisible = scannerAvailable
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_qr_scanner -> {
                scanRequestLauncher?.launch(scanIntent)
                true
            }
            else -> false
        }
    }

    private fun prepareCredential(server: String, username: String, token: String) {
        // As stated in <a href="https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html#obtaining-the-login-credentials">Nextcloud document</a>:
        // The server may specify a protocol (http or https). If no protocol is specified the client will assume https.
        val host = if (server.startsWith("http")) server else "https://${server}"
        val currentUsername = authenticateModel.getAccount().username

        authenticateModel.setToken(username, token, host)

        if (reLogin) {
            if (username != currentUsername) {
                // Re-login to a new account
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.login_to_new_account), getString(R.string.yes_logout), true, CONFIRM_NEW_ACCOUNT_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
            } else {
                saveCredential()
                parentFragmentManager.popBackStack()
            }
        } else {
            saveCredential()
            authenticateModel.setAuthResult(NCLoginFragment.AuthenticateViewModel.RESULT_SUCCESS)
            parentFragmentManager.popBackStack()
        }
    }

    private fun saveCredential() {
        val ncAccount = authenticateModel.getAccount()
        val url = URL(ncAccount.serverUrl)
        val account: Account

        AccountManager.get(requireContext()).run {
            if (!reLogin) {
                account = Account("${ncAccount.username}@${url.host}", getString(R.string.account_type_nc))
                addAccountExplicitly(account, "", null)
            } else {
                account = getAccountsByType(getString(R.string.account_type_nc))[0]
            }

            setAuthToken(account, ncAccount.serverUrl, ncAccount.token)    // authTokenType set to server address
            setUserData(account, getString(R.string.nc_userdata_server), ncAccount.serverUrl)
            setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
            setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
            setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
            setUserData(account, getString(R.string.nc_userdata_username), ncAccount.username)
            setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("${ncAccount.username}:${ncAccount.token}".encodeToByteArray(), Base64.NO_WRAP))
            setUserData(account, getString(R.string.nc_userdata_selfsigned), ncAccount.selfSigned.toString())
            notifyAccountAuthenticated(account)
        }
    }

/*
    private fun getCredential(url: String): HashMap<String, String>? {
        val credential = HashMap<String, String>()
        // Login flow v1 result: nc://login/server:<server>&user:<loginname>&password:<password>
        // QR code scanning result: nc://login/user:<loginname>&password:<password>&server:<server>
        // In case Nextcloud will ever change the return url
        return if (url.startsWith(resources.getString(R.string.nextcloud_credential_scheme))) {
            ("(.*):(.*)&(.*):(.*)&(.*):(.*)").toRegex().matchEntire(url.substringAfter("nc://login/"))?.destructured?.let { (k1, v1, k2, v2, k3, v3) ->
                try {
                    credential[k1] = v1
                    credential[k2] = v2
                    credential[k3] = v3

                    when {
                        credential["server"] == null -> null
                        credential["user"] == null -> null
                        credential["password"] == null -> null
                        else -> credential
                    }
                } catch (e: Exception) { null }
            }
        } else null
    }
*/

    companion object {
        private const val LOGIN_FLOW_ENDPOINT = "/index.php/login/flow"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val CONFIRM_NEW_ACCOUNT_DIALOG = "CONFIRM_NEW_ACCOUNT_DIALOG"

        private const val KEY_ACTION_BAR_HEIGHT = "KEY_ACTION_BAR_HEIGHT"

        private const val KEY_RELOGIN = "KEY_RELOGIN"
        private const val KEY_THEMING = "KEY_THEMING"
        @JvmStatic
        fun newInstance(reLogin: Boolean, theming: NCLoginFragment.AuthenticateViewModel.NCThemimg = NCLoginFragment.AuthenticateViewModel.NCThemimg()) = NCAuthenticationFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_RELOGIN, reLogin)
                putParcelable(KEY_THEMING, theming)
            }
        }
    }
}