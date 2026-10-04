package com.example.instazen

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        private var runtime: GeckoRuntime? = null

        // Maximum exception allowance per calendar day.
        private const val MAX_EXCEPTION_MS = 15 * 60 * 1000L

        // Persistent storage.
        private const val PREFS_NAME = "instazen_limits"
        private const val PREF_DATE = "exception_date"
        private const val PREF_USED_MS = "exception_used_ms"
    }

    private lateinit var session: GeckoSession

    // UI
    private lateinit var exceptionButton: Button
    private lateinit var timerText: TextView

    // Timer
    private val handler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimer()

            if (isTimerRunning()) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    // Instagram
    private val instagramHost = "www.instagram.com"
    private val dmPath = "/direct/"
    private val dmUrl =
        "https://www.instagram.com/direct/inbox/"

    // Login state
    private var loginPhase = true

    // Prevent recursive DM redirects.
    private var redirecting = false

    // ---------------------------------------------------------
    // Exception state
    // ---------------------------------------------------------

    /*
     * true = user has enabled the exception.
     *
     * IMPORTANT:
     * Even when this is true, if inMessages == true,
     * the timer is paused and restricted mode is enforced.
     */
    private var exceptionEnabled = false

    // User manually paused the exception.
    private var exceptionManuallyPaused = false

    // Whether the current Instagram page is a DM page.
    private var inMessages = false

    // Start time of the currently counting timer segment.
    private var exceptionStartTime = 0L

    // Activity foreground state.
    private var activityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // =====================================================
        // ROOT LAYOUT
        // =====================================================

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val geckoView = GeckoView(this)

        // GeckoView gets all space except the InstaZen control bar.
        root.addView(
            geckoView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // =====================================================
        // INSTEZEN CONTROL BAR
        // =====================================================

        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(25, 25, 25))

            setPadding(
                dp(8),
                dp(4),
                dp(8),
                dp(4)
            )
        }

        timerText = TextView(this).apply {
            text = "Exception: 15:00"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        exceptionButton = Button(this).apply {
            text = "Start 15 min"
            textSize = 12f

            setOnClickListener {
                toggleException()
            }
        }

        controlBar.addView(
            timerText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        controlBar.addView(
            exceptionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            controlBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // =====================================================
        // SYSTEM BAR INSETS
        // =====================================================

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->

            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            WindowInsetsCompat.CONSUMED
        }

        setContentView(root)

        // =====================================================
        // GECKOVIEW INITIALIZATION
        // =====================================================

        if (runtime == null) {
            runtime = GeckoRuntime.create(this)
        }

        session = GeckoSession()

        session.navigationDelegate =
            object : GeckoSession.NavigationDelegate {

                // =================================================
                // LOAD REQUEST
                // =================================================

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny> {

                    val uri = Uri.parse(request.uri)

                    val host = uri.host ?: ""
                    val path = uri.path ?: "/"

                    // -------------------------------------------------
                    // EXCEPTION MODE
                    //
                    // Exception only allows unrestricted browsing
                    // when we are NOT inside Messages.
                    // -------------------------------------------------

                    if (
                        exceptionEnabled &&
                        !exceptionManuallyPaused &&
                        !inMessages
                    ) {
                        return GeckoResult.allow()
                    }

                    // -------------------------------------------------
                    // LOGIN / AUTHENTICATION
                    // -------------------------------------------------

                    if (
                        loginPhase &&
                        host == instagramHost &&
                        path.startsWith("/accounts/")
                    ) {
                        return GeckoResult.allow()
                    }

                    // -------------------------------------------------
                    // DM IS ALWAYS ALLOWED
                    // -------------------------------------------------

                    if (
                        host == instagramHost &&
                        path.startsWith(dmPath)
                    ) {
                        loginPhase = false
                        inMessages = true

                        // Exception timer must stop in Messages.
                        stopExceptionTimer()

                        updateTimer()
                        updateExceptionButton()

                        return GeckoResult.allow()
                    }

                    // -------------------------------------------------
                    // RESTRICTED MODE
                    //
                    // Anything other than DM is blocked.
                    // -------------------------------------------------

                    if (host == instagramHost) {

                        loginPhase = false

                        redirectToDm()

                        return GeckoResult.deny()
                    }

                    // -------------------------------------------------
                    // EXTERNAL WEBSITES
                    // -------------------------------------------------

                    redirectToDm()

                    return GeckoResult.deny()
                }

                // =================================================
                // LOCATION CHANGE
                // =================================================

                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {

                    if (url == null || redirecting) {
                        return
                    }

                    val uri = Uri.parse(url)

                    val host = uri.host ?: ""
                    val path = uri.path ?: "/"

                    val nowInMessages =
                        host == instagramHost &&
                                path.startsWith(dmPath)

                    // -------------------------------------------------
                    // ENTERED MESSAGES
                    // -------------------------------------------------

                    if (nowInMessages) {

                        inMessages = true

                        // Exception timer pauses here.
                        stopExceptionTimer()

                        updateTimer()
                        updateExceptionButton()

                        return
                    }

                    // -------------------------------------------------
                    // LEFT MESSAGES
                    // -------------------------------------------------

                    inMessages = false

                    // -------------------------------------------------
                    // Exception is active and not manually paused.
                    // Therefore unrestricted browsing is allowed.
                    // -------------------------------------------------

                    if (
                        exceptionEnabled &&
                        !exceptionManuallyPaused
                    ) {
                        startExceptionTimerIfNeeded()

                        updateTimer()
                        updateExceptionButton()

                        return
                    }

                    // -------------------------------------------------
                    // Restricted mode.
                    //
                    // Return to DM.
                    // -------------------------------------------------

                    redirectToDm()

                    updateTimer()
                    updateExceptionButton()
                }
            }

        // Open Gecko session.
        session.open(runtime!!)

        geckoView.setSession(session)

        // Initial Instagram login.
        session.loadUri(
            "https://www.instagram.com/accounts/login/"
        )

        updateTimer()
        updateExceptionButton()
    }

    // =========================================================
    // EXCEPTION BUTTON
    // =========================================================

    private fun toggleException() {

        // ---------------------------------------------------------
        // Currently running → PAUSE
        // ---------------------------------------------------------

        if (
            exceptionEnabled &&
            !exceptionManuallyPaused
        ) {
            pauseExceptionManually()
            return
        }

        // ---------------------------------------------------------
        // Currently paused → RESUME
        // ---------------------------------------------------------

        val remaining = getRemainingExceptionMs()

        if (remaining <= 0L) {

            exceptionButton.text = "Used today"
            exceptionButton.isEnabled = false

            return
        }

        exceptionEnabled = true
        exceptionManuallyPaused = false

        /*
         * If we are currently in Messages, don't start counting.
         * The user can resume browsing outside Messages later.
         */
        if (!inMessages) {
            startExceptionTimerIfNeeded()
        }

        updateTimer()
        updateExceptionButton()
    }

    // =========================================================
    // MANUAL PAUSE
    // =========================================================

    private fun pauseExceptionManually() {

        saveCurrentExceptionUsage()

        exceptionEnabled = false
        exceptionManuallyPaused = true

        stopExceptionTimer()

        updateTimer()
        updateExceptionButton()

        /*
         * Manual pause means restricted mode immediately.
         *
         * If we're not already in Messages, return there.
         */
        if (!inMessages) {
            redirectToDm()
        }
    }

    // =========================================================
    // TIMER STATE
    // =========================================================

    private fun isTimerRunning(): Boolean {

        return exceptionEnabled &&
                !exceptionManuallyPaused &&
                !inMessages &&
                activityResumed
    }

    // =========================================================
    // START TIMER
    // =========================================================

    private fun startExceptionTimerIfNeeded() {

        if (!isTimerRunning()) {
            return
        }

        if (exceptionStartTime == 0L) {

            exceptionStartTime =
                android.os.SystemClock.elapsedRealtime()

            handler.removeCallbacks(timerRunnable)

            handler.post(timerRunnable)
        }
    }

    // =========================================================
    // STOP TIMER
    // =========================================================

    private fun stopExceptionTimer() {

        if (exceptionStartTime != 0L) {
            saveCurrentExceptionUsage()
        }

        exceptionStartTime = 0L

        handler.removeCallbacks(timerRunnable)
    }

    // =========================================================
    // TIMER DISPLAY
    // =========================================================

    private fun updateTimer() {

        val remaining = getRemainingExceptionMs()

        if (remaining <= 0L) {

            timerText.text = "Exception: 00:00"

            if (exceptionEnabled) {

                exceptionEnabled = false
                exceptionManuallyPaused = false

                stopExceptionTimer()

                /*
                 * 15 minutes are exhausted.
                 * Return to restricted DM mode.
                 */
                if (!inMessages) {
                    redirectToDm()
                }
            }

            updateExceptionButton()

            return
        }

        val totalSeconds = remaining / 1000L

        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        timerText.text = String.format(
            "Exception: %02d:%02d",
            minutes,
            seconds
        )

        updateExceptionButton()
    }

    // =========================================================
    // BUTTON TEXT
    // =========================================================

    private fun updateExceptionButton() {

        if (getRemainingExceptionMs() <= 0L) {

            exceptionButton.text = "Used today"
            exceptionButton.isEnabled = false

            return
        }

        exceptionButton.isEnabled = true

        // -----------------------------------------------------
        // Exception enabled
        // -----------------------------------------------------

        if (
            exceptionEnabled &&
            !exceptionManuallyPaused
        ) {

            if (inMessages) {

                /*
                 * Exception remains available but is paused
                 * automatically while in Messages.
                 */
                exceptionButton.text =
                    "Resume outside Messages"

            } else {

                exceptionButton.text =
                    "Pause Exception"
            }

            return
        }

        // -----------------------------------------------------
        // Exception manually paused
        // -----------------------------------------------------

        if (exceptionManuallyPaused) {

            exceptionButton.text =
                "Resume Exception"

            return
        }

        // -----------------------------------------------------
        // Not active
        // -----------------------------------------------------

        exceptionButton.text =
            "Start 15 min"
    }

    // =========================================================
    // DAILY STORAGE
    // =========================================================

    private fun getUsedExceptionMs(): Long {

        val prefs = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        val savedDate =
            prefs.getString(PREF_DATE, null)

        val today =
            LocalDate.now().toString()

        /*
         * New calendar day:
         * reset the daily allowance.
         */
        if (savedDate != today) {
            return 0L
        }

        return prefs.getLong(
            PREF_USED_MS,
            0L
        ).coerceIn(
            0L,
            MAX_EXCEPTION_MS
        )
    }

    // =========================================================
    // REMAINING TIME
    // =========================================================

    private fun getRemainingExceptionMs(): Long {

        var used = getUsedExceptionMs()

        /*
         * Include the currently running timer segment.
         */
        if (exceptionStartTime != 0L) {

            val currentSegment =
                android.os.SystemClock.elapsedRealtime() -
                        exceptionStartTime

            used += currentSegment
        }

        return (
                MAX_EXCEPTION_MS - used
                ).coerceAtLeast(0L)
    }

    // =========================================================
    // SAVE CURRENT TIMER SEGMENT
    // =========================================================

    private fun saveCurrentExceptionUsage() {

        if (exceptionStartTime == 0L) {
            return
        }

        val elapsed =
            android.os.SystemClock.elapsedRealtime() -
                    exceptionStartTime

        if (elapsed <= 0L) {

            exceptionStartTime = 0L

            return
        }

        val oldUsed =
            getUsedExceptionMs()

        val newUsed =
            (oldUsed + elapsed)
                .coerceAtMost(MAX_EXCEPTION_MS)

        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

        prefs.edit()
            .putString(
                PREF_DATE,
                LocalDate.now().toString()
            )
            .putLong(
                PREF_USED_MS,
                newUsed
            )
            .apply()

        exceptionStartTime = 0L
    }

    // =========================================================
    // ACTIVITY FOREGROUND
    // =========================================================

    override fun onResume() {

        super.onResume()

        activityResumed = true

        /*
         * If exception is active and we're not in Messages,
         * continue counting.
         */
        if (!inMessages) {
            startExceptionTimerIfNeeded()
        }

        updateTimer()
        updateExceptionButton()
    }

    // =========================================================
    // ACTIVITY BACKGROUND
    // =========================================================

    override fun onPause() {

        /*
         * Save any time used before the Activity leaves
         * the foreground.
         */
        stopExceptionTimer()

        activityResumed = false

        super.onPause()

        updateTimer()
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        handler.removeCallbacks(timerRunnable)

        super.onDestroy()
    }

    // =========================================================
    // REDIRECT TO DM
    // =========================================================

    private fun redirectToDm() {

        if (redirecting) {
            return
        }

        redirecting = true

        /*
         * This flag bypasses our own onLoadRequest callback,
         * preventing a redirect loop.
         */
        session.load(
            GeckoSession.Loader()
                .uri(dmUrl)
                .flags(
                    GeckoSession.LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE
                )
        )

        redirecting = false
    }

    // =========================================================
    // DP → PX
    // =========================================================

    private fun dp(value: Int): Int {

        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }
}