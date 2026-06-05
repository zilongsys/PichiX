package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.analyzer.FlexBlockOffer
import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexReturnTriggersEvaluator
import com.oceanlab.pichix.data.FlexReturnTriggersStore
import com.oceanlab.pichix.data.FlexReturnScreenTrigger
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.flex.FlexIds
import com.oceanlab.pichix.util.ScreenTextMatcher
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.allTextsByViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.performClickOnClickableSelfOrAncestor
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.findClickableByExactText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.findClickableByText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.firstTextByViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.getAllText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.getAllVisibleText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.hasViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.recycleNodes
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.useViewIdNodes
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.withAllObtainedNodes

/**
 * Lectura/clic sobre Flex vía [AccessibilityService.rootInActiveWindow] (comportamiento v0.1.7).
 * Los filtros de texto en Config se aplican de forma permisiva para no bloquear el motor.
 */
class FlexScreenReader(private val service: AccessibilityService) {

    private val appPackage: String
        get() = com.oceanlab.pichix.data.MonitorPackages.primaryTarget(service)
            ?: "com.amazon.rabbit"

    private fun activeRoot(): AccessibilityNodeInfo? = service.rootInActiveWindow

    fun resolveId(suffix: String): String? {
        val candidates = FlexIds.viewIdCandidates(suffix, appPackage)
        val root = activeRoot()
        if (root != null) {
            try {
                for (cand in candidates) {
                    if (root.hasViewId(cand)) return cand
                }
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return candidates.firstOrNull()
    }

    fun readFullScreenText(): String {
        val root = activeRoot() ?: return ""
        return try {
            root.getAllVisibleText()
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun withActiveRoot(block: (AccessibilityNodeInfo) -> Unit) {
        val root = activeRoot() ?: return
        try {
            block(root)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun detectScreenFlags(screenText: String, settings: AppSettings? = null): ScreenFlags {
        val lower = screenText.lowercase()
        val triggers = settings?.let { FlexReturnTriggersStore.load(it) }.orEmpty()
        return ScreenFlags(
            blockUnavailable = lower.contains("no longer available") ||
                lower.contains("not available") ||
                lower.contains("unavailable"),
            captcha = lower.contains("captcha") || lower.contains("robot") ||
                lower.contains("puzzle") || lower.contains("verify"),
            offerScheduled = lower.contains("scheduled") && lower.contains("offer"),
            onOffersList = isOnOffersListScreen(lower),
            onFlexHomeTabs = lower.contains("updates") && lower.contains("schedule") && !isOnOffersListScreen(lower),
            shouldReturnToOffers = shouldReturnToOffersScreen(screenText, triggers),
        )
    }

    /**
     * Lista de ofertas: filas offer_pay visibles o texto «filter offers by» / filtrar.
     * Si esto es true, Return 2 automático no debe ejecutarse.
     */
    fun isOnOffersListScreen(screenText: String? = null): Boolean {
        if (hasAnyOfferPay()) return true
        val lower = (screenText ?: readFullScreenText()).lowercase()
        return lower.contains("filter offers by") ||
            lower.contains("filtrar ofertas") ||
            lower.contains("filtrar por")
    }

    /**
     * Return 2 automático: lee la pantalla; si NO estás en ofertas y algún disparador activo coincide → true.
     */
    fun shouldReturnToOffersScreen(
        screenText: String? = null,
        triggers: List<FlexReturnScreenTrigger> = emptyList(),
    ): Boolean {
        val text = screenText ?: readFullScreenText()
        val lower = text.lowercase()
        if (lower.contains("captcha") || lower.contains("robot") ||
            lower.contains("puzzle") || lower.contains("verify")
        ) {
            return false
        }
        if (isOnOffersListScreen(text)) return false
        return FlexReturnTriggersEvaluator.anyMatches(text, triggers.filter { it.enabled })
    }

    private fun hasAnyOfferPay(): Boolean {
        val id = resolveId(FlexIds.OFFER_PAY) ?: return false
        val root = activeRoot() ?: return false
        return try {
            root.hasViewId(id)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun readOffersFromList(): List<FlexBlockOffer> {
        val payId = resolveId(FlexIds.OFFER_PAY) ?: return emptyList()
        val timeId = resolveId(FlexIds.OFFER_TIME)
        val stationId = resolveId(FlexIds.OFFER_STATION)
        val durationId = resolveId(FlexIds.LEFT_SECONDARY_LABEL)

        val root = activeRoot() ?: return emptyList()
        return try {
            root.useViewIdNodes(payId) { payNodes ->
                payNodes.mapIndexed { index, payNode ->
                    val payText = payNode.text?.toString()?.trim().orEmpty()
                    val pay = FlexGrabberEvaluator.parsePay(payText)
                    val timeText = findSiblingText(payNode, timeId, index)
                    val stationText = findSiblingText(payNode, stationId, index)
                    val durationText = findSiblingText(payNode, durationId, index)
                    val durationHours = FlexGrabberEvaluator.resolveDurationHours(timeText, durationText)
                    val hourly = pay?.let { p ->
                        durationHours?.takeIf { it > 0 }?.let { p / it }
                            ?: FlexGrabberEvaluator.hourlyFromPayAndTime(p, timeText, durationText)
                    }
                    FlexBlockOffer(
                        index = index,
                        payText = payText,
                        timeText = timeText,
                        stationText = stationText,
                        payAmount = pay,
                        startHour = FlexGrabberEvaluator.parseStartHour(timeText),
                        durationHours = durationHours,
                        hourlyRate = hourly,
                    ).also { offer ->
                        FlexState.putOfferField("$payId#$index", payText)
                        timeId?.let { FlexState.putOfferField("$it#$index", timeText) }
                        stationId?.let { FlexState.putOfferField("$it#$index", stationText) }
                    }
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun findSiblingText(anchor: AccessibilityNodeInfo, fullId: String?, index: Int): String {
        if (fullId == null) return ""
        val root = activeRoot() ?: return ""
        return try {
            val texts = root.allTextsByViewId(fullId)
            texts.getOrElse(index) { texts.firstOrNull().orEmpty() }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun readBlockDetails(): Map<String, String> {
        FlexState.clearBlockDetails()
        val fields = mapOf(
            FlexIds.OFFER_DETAILS_STATION to "station",
            FlexIds.PAY_RANGE_WITH_TIPS to "pay_range",
            FlexIds.OFFER_TIME_WINDOW to "time_window",
            FlexIds.OFFER_DATE to "date",
        )
        val root = activeRoot() ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        return try {
            for ((suffix, key) in fields) {
                val id = resolveId(suffix) ?: continue
                val text = root.firstTextByViewId(id).orEmpty()
                if (text.isNotEmpty()) {
                    out[key] = text
                    FlexState.putBlockDetail(id, text)
                }
            }
            out
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /** Abre la tarjeta de la oferta: sube desde offer_pay#index hasta id/card (alineación fiable). */
    fun clickOfferCardAtIndex(index: Int): Boolean {
        val payId = resolveId(FlexIds.OFFER_PAY) ?: return false
        val cardId = resolveId(FlexIds.OFFER_CARD) ?: return false
        val root = activeRoot() ?: return false
        return try {
            root.useViewIdNodes(payId) { payNodes ->
                val payNode = payNodes.getOrNull(index) ?: return@useViewIdNodes false
                var cur: AccessibilityNodeInfo? = payNode
                var depth = 0
                while (cur != null && depth < 24) {
                    if (cur.viewIdResourceName == cardId) {
                        val clickable = if (cur.isClickable) cur else findClickableParent(cur)
                        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        if (clickable != null && clickable !== cur) {
                            try {
                                clickable.recycle()
                            } catch (_: Exception) {
                            }
                        }
                        return@useViewIdNodes ok
                    }
                    val parent = cur.parent
                    if (cur !== payNode) {
                        try {
                            cur.recycle()
                        } catch (_: Exception) {
                        }
                    }
                    cur = parent
                    depth++
                }
                false
            }
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur.isClickable) return AccessibilityNodeInfo.obtain(cur)
            val parent = cur.parent
            if (cur !== node) {
                try {
                    cur.recycle()
                } catch (_: Exception) {
                }
            }
            cur = parent
            depth++
        }
        return null
    }

    fun clickScheduleOnList(index: Int = 0): Boolean = clickOfferCardAtIndex(index)

    fun clickScheduleOnDetail(): Boolean {
        val root = activeRoot() ?: return false
        return try {
            val byText = root.findClickableByText("Schedule", ignoreCase = true)
                ?: root.findClickableByText("Scheduled", ignoreCase = true)
            if (byText != null) {
                val ok = byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                try { byText.recycle() } catch (_: Exception) {}
                if (ok) return true
            }
            val id = resolveId(FlexIds.MERIDIAN_BUTTON_TEXT) ?: return false
            root.useViewIdNodes(id) { nodes ->
                val schedule = nodes.firstOrNull { n ->
                    val t = n.text?.toString().orEmpty()
                    t.contains("Schedule", ignoreCase = true)
                } ?: nodes.firstOrNull()
                schedule?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun clickMeridianButton(index: Int): Boolean {
        val id = resolveId(FlexIds.MERIDIAN_BUTTON_TEXT) ?: return false
        val root = activeRoot() ?: return false
        return try {
            root.useViewIdNodes(id) { nodes ->
                val target = nodes.getOrNull(index) ?: nodes.firstOrNull()
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /** Filtro de pantalla para el clic Refresh: vacío = siempre; si no, usa modo e ignoreCase de Config. */
    fun screenMatchesForClick(
        requiredScreenText: String,
        screenText: String? = null,
        matchMode: String = AppSettings.TEXT_MATCH_CONTAINS,
        ignoreCase: Boolean = true,
    ): Boolean {
        if (requiredScreenText.isBlank()) return true
        val text = screenText ?: readFullScreenText()
        return ScreenTextMatcher.matches(text, requiredScreenText, matchMode, ignoreCase)
    }

    fun clickTargetButton(
        buttonText: String,
        matchMode: String = AppSettings.TEXT_MATCH_EXACT,
        ignoreCase: Boolean = true,
    ): Boolean {
        if (buttonText.isBlank()) return false
        val root = activeRoot() ?: return false
        return try {
            if (buttonText.equals("Refresh", ignoreCase = ignoreCase)) {
                if (clickPrimaryFooterButton(root)) return true
                if (clickMeridianFooterButton(root, buttonText, matchMode, ignoreCase)) return true
            }
            val node = findClickableButtonNode(root, buttonText, matchMode, ignoreCase)
                ?: if (matchMode == AppSettings.TEXT_MATCH_EXACT) {
                    findClickableButtonNode(root, buttonText, AppSettings.TEXT_MATCH_CONTAINS, ignoreCase)
                } else {
                    null
                }
            if (node != null) {
                val ok = node.performClickOnClickableSelfOrAncestor()
                try {
                    node.recycle()
                } catch (_: Exception) {
                }
                ok
            } else {
                false
            }
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun findClickableButtonNode(
        root: AccessibilityNodeInfo,
        buttonText: String,
        matchMode: String,
        ignoreCase: Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        root.withAllObtainedNodes { nodes: List<AccessibilityNodeInfo> ->
            for (n in nodes) {
                val label = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (!ScreenTextMatcher.matches(label, buttonText, matchMode, ignoreCase)) continue
                found = AccessibilityNodeInfo.obtain(n)
                break
            }
        }
        return found
    }

    /** Footer Flex: primaryButton (sube al ancestro clickable si el id no lo es). */
    private fun clickPrimaryFooterButton(root: AccessibilityNodeInfo): Boolean {
        val primaryId = resolveId(FlexIds.PRIMARY_BUTTON) ?: return false
        return root.useViewIdNodes(primaryId) { nodes ->
            nodes.any { it.performClickOnClickableSelfOrAncestor() }
        }
    }

    /** meridian_button_text_view con texto Refresh (u otro configurado). */
    private fun clickMeridianFooterButton(
        root: AccessibilityNodeInfo,
        buttonText: String,
        matchMode: String,
        ignoreCase: Boolean,
    ): Boolean {
        val id = resolveId(FlexIds.MERIDIAN_BUTTON_TEXT) ?: return false
        return root.useViewIdNodes(id) { nodes ->
            nodes.any { n ->
                val label = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                ScreenTextMatcher.matches(label, buttonText, matchMode, ignoreCase) &&
                    n.performClickOnClickableSelfOrAncestor()
            }
        }
    }

    fun clickRefresh(): Boolean = clickTargetButton("Refresh")

    /**
     * Menú ≡ (3 rayas) del macro Return_2 — abre el drawer lateral de Flex.
     * Busca por contentDescription, ids conocidos o botón clickable arriba-izquierda.
     */
    fun clickFlexDrawerMenu(): Boolean {
        val root = activeRoot() ?: return false
        return try {
            val menuDescNeedles = listOf(
                "open navigation drawer",
                "open drawer",
                "navigation menu",
                "show navigation",
                "menu",
            )
            root.withAllObtainedNodes { nodes ->
                for (n in nodes) {
                    val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
                    if (menuDescNeedles.any { desc.contains(it) } &&
                        n.performClickOnClickableSelfOrAncestor()
                    ) {
                        return@withAllObtainedNodes true
                    }
                }
                for (suffix in FlexIds.NAV_MENU_ID_SUFFIXES) {
                    val id = resolveId(suffix) ?: continue
                    val clicked = root.useViewIdNodes(id) { list ->
                        list.any { it.performClickOnClickableSelfOrAncestor() }
                    }
                    if (clicked) return@withAllObtainedNodes true
                }
                val dm = service.resources.displayMetrics
                val maxX = dm.widthPixels * 0.22f
                val maxY = dm.heightPixels * 0.2f
                for (n in nodes) {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.left > maxX || rect.top > maxY || rect.width() !in 1..220) continue
                    val text = n.text?.toString().orEmpty()
                    if (text.isNotBlank() && text.length > 4) continue
                    if (n.performClickOnClickableSelfOrAncestor()) {
                        return@withAllObtainedNodes true
                    }
                }
                false
            }
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Pestaña inferior Flex (Updates / Schedule). */
    fun clickTabByLabel(label: String): Boolean {
        val tabId = resolveId(FlexIds.MERIDIAN_TAB_ITEM_LABEL) ?: return false
        val root = activeRoot() ?: return false
        return try {
            root.useViewIdNodes(tabId) { nodes ->
                nodes.any { n ->
                    val t = n.text?.toString().orEmpty()
                    t.equals(label, ignoreCase = true) && n.performClickOnClickableSelfOrAncestor()
                }
            }
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun clickTextButton(text: String, partial: Boolean = false): Boolean =
        clickTargetButton(
            text,
            if (partial) AppSettings.TEXT_MATCH_CONTAINS else AppSettings.TEXT_MATCH_EXACT,
            ignoreCase = true,
        )

    /**
     * Contenido Flex visible (OR): paquete Flex en ventana activa, ids Flex, lista de ofertas
     * parseada o texto típico de pantalla de ofertas.
     */
    fun isFlexForegroundUi(): Boolean {
        val target = appPackage

        activeRoot()?.let { root ->
            try {
                if (root.packageName?.toString() == target) return true
                if (hasFlexUiMarkers(root)) return true
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (win in service.windows ?: emptyList()) {
                when (win.type) {
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    AccessibilityWindowInfo.TYPE_SYSTEM -> continue
                }
                val root = win.root ?: continue
                try {
                    if (root.packageName?.toString() == target) return true
                    if (hasFlexUiMarkers(root)) return true
                } finally {
                    try {
                        root.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (hasAnyOfferPay()) return true
        if (readOffersFromList().isNotEmpty()) return true

        val screen = readFullScreenText()
        if (detectScreenFlags(screen).onOffersList) return true
        val lower = screen.lowercase()
        return lower.contains("refresh") &&
            (lower.contains("offer") || lower.contains("filter") || lower.contains("block"))
    }

    private fun hasFlexUiMarkers(root: AccessibilityNodeInfo): Boolean {
        val idMarkers = listOf(
            FlexIds.OFFER_PAY,
            FlexIds.LIST_RECYCLER,
            FlexIds.PRIMARY_BUTTON,
            FlexIds.FILTER_OFFER_COUNT,
            FlexIds.OFFER_CARD,
            FlexIds.MERIDIAN_BUTTON_TEXT,
        )
        for (suffix in idMarkers) {
            if (rootHasViewId(root, suffix)) return true
        }
        val text = root.getAllVisibleText().lowercase()
        if (text.contains("refresh") && (text.contains("offer") || text.contains("filter"))) {
            return true
        }
        return detectScreenFlags(text).onOffersList
    }

    private fun rootHasViewId(root: AccessibilityNodeInfo, suffix: String): Boolean {
        for (candidate in FlexIds.viewIdCandidates(suffix, appPackage)) {
            if (root.hasViewId(candidate)) return true
        }
        return false
    }

    fun clickBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun offerListSignature(): String {
        return readOffersFromList().joinToString("|") { o ->
            "${o.payText}:${o.timeText}:${o.stationText}"
        }
    }

    private val listScroller by lazy { FlexListScroller(service, this) }

    fun scrollDown(onFinished: ((Boolean) -> Unit)? = null): Boolean =
        scrollInZone(down = true, onFinished)

    fun scrollUp(onFinished: ((Boolean) -> Unit)? = null): Boolean =
        scrollInZone(down = false, onFinished)

    private fun scrollInZone(down: Boolean, onFinished: ((Boolean) -> Unit)?): Boolean {
        var dispatched = false
        listScroller.scrollInOfferZone(down) { ok ->
            dispatched = ok
            onFinished?.invoke(ok)
        }
        return dispatched
    }

    data class ScreenFlags(
        val blockUnavailable: Boolean = false,
        val captcha: Boolean = false,
        val offerScheduled: Boolean = false,
        val onOffersList: Boolean = false,
        val onFlexHomeTabs: Boolean = false,
        val shouldReturnToOffers: Boolean = false,
    )
}
