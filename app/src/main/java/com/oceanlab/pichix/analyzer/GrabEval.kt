package com.oceanlab.pichix.analyzer

data class GrabEval(
    val result: FlexGrabResult,
    val reason: String = "",
) {
    val accepted: Boolean
        get() = result == FlexGrabResult.ACCEPT || result == FlexGrabResult.SIMULATED_ACCEPT

    companion object {
        fun accept(reason: String) = GrabEval(FlexGrabResult.ACCEPT, reason)
        fun simulated(reason: String) = GrabEval(FlexGrabResult.SIMULATED_ACCEPT, reason)
        fun reject(reason: String) = GrabEval(FlexGrabResult.REJECT, reason)
        fun skip(reason: String) = GrabEval(FlexGrabResult.SKIP, reason)
    }
}
