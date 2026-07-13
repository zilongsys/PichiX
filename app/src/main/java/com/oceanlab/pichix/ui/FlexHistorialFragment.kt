package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class FlexHistorialFragment : Fragment() {

    private var logger: OfferLogger? = null
    private var adapter: OfferLogAdapter? = null
    private val offerReceiver = OfferBroadcastReceiver()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private val refreshRunnable = Runnable {
        refreshScheduled = false
        val rv = view?.findViewById<RecyclerView>(R.id.rvOffers) ?: return@Runnable
        refreshHistory(rv)
    }
    private lateinit var settings: AppSettings
    private lateinit var tvSavePath: TextView
    private lateinit var tvHistAccepted: TextView
    private lateinit var tvHistRejected: TextView
    private lateinit var tvHistCancelled: TextView
    private lateinit var tvHistSimulated: TextView
    private lateinit var tvHistSeen: TextView
    private lateinit var tvHistMiss: TextView
    private var tvHistEmpty: TextView? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.txtSaveUri = uri.toString()
            updateSavePathLabel(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_historial, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        logger = OfferLogger(requireContext())

        tvHistAccepted = view.findViewById(R.id.tvHistAccepted)
        tvHistRejected = view.findViewById(R.id.tvHistRejected)
        tvHistCancelled = view.findViewById(R.id.tvHistCancelled)
        tvHistSimulated = view.findViewById(R.id.tvHistSimulated)
        tvHistSeen = view.findViewById(R.id.tvHistSeen)
        tvHistMiss = view.findViewById(R.id.tvHistMiss)
        tvHistEmpty = view.findViewById(R.id.tvHistEmpty)
        tvSavePath = view.findViewById(R.id.tvTxtSavePath)

        fun hint(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        view.findViewById<LinearLayout>(R.id.cardAccepted).setOnClickListener {
            hint("✅ Aceptadas: bloques que PichiX tomó hoy.")
        }
        view.findViewById<LinearLayout>(R.id.cardRejected).setOnClickListener {
            hint("❌ Rechazadas: ofertas que no cumplen tus criterios.")
        }
        view.findViewById<LinearLayout>(R.id.cardCancelled).setOnClickListener {
            hint("🚫 Canceladas: entradas marcadas manualmente en el log del día.")
        }
        view.findViewById<LinearLayout>(R.id.cardSimulated).setOnClickListener {
            hint("🧪 Simuladas: modo simulación — habría tomado el bloque.")
        }
        view.findViewById<LinearLayout>(R.id.cardMiss).setOnClickListener {
            hint("💨 Perdidas: intento de tomar pero el bloque ya no estaba disponible.")
        }
        view.findViewById<LinearLayout>(R.id.cardSeen).setOnClickListener {
            hint("👁 Vistas: ofertas únicas evaluadas hoy.")
        }

        listOf(R.id.btnExportCsv, R.id.btnExportTxt, R.id.btnResetToday, R.id.btnClearHistory).forEach { id ->
            view.findViewById<MaterialButton>(id).centerHistorialActionContent()
        }

        view.findViewById<MaterialButton>(R.id.btnDayLog).setOnClickListener {
            startActivity(Intent(requireContext(), DayLogActivity::class.java))
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvOffers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.setOnTouchListener(object : View.OnTouchListener {
            private val detector = GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        startActivity(Intent(requireContext(), DayLogActivity::class.java))
                        return true
                    }
                },
            )

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                detector.onTouchEvent(event)
                return false
            }
        })

        view.findViewById<MaterialButton>(R.id.btnExportCsv).setOnClickListener {
            shareFile(logger!!.getLogFilePath(), "text/csv", "Compartir historial CSV")
        }
        view.findViewById<MaterialButton>(R.id.btnConfigSavePath).setOnClickListener {
            folderPicker.launch(null)
        }
        view.findViewById<MaterialButton>(R.id.btnExportTxt).setOnClickListener {
            val uriStr = settings.txtSaveUri
            if (uriStr.isBlank()) {
                Toast.makeText(context, "Primero configura la ruta de guardado", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = logger!!.saveTxtToUri(Uri.parse(uriStr))
            if (fileName != null) {
                Toast.makeText(context, "✓ Guardado: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Error al guardar — verifica la ruta", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<MaterialButton>(R.id.btnResetToday).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle("Resetear datos de hoy")
                .setMessage("Se eliminarán todas las ofertas registradas hoy. Los días anteriores se conservan.\n\n¿Continuar?")
                .setPositiveButton("Resetear") { _, _ ->
                    logger!!.resetToday()
                    refreshHistory(rv)
                    Toast.makeText(context, "✓ Datos de hoy eliminados", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        view.findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle("Borrar historial")
                .setMessage("¿Seguro? Se eliminarán todos los registros.")
                .setPositiveButton("Borrar") { _, _ ->
                    logger!!.clearHistory()
                    refreshHistory(rv)
                    Toast.makeText(context, "Historial borrado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        val uriStr = settings.txtSaveUri
        if (uriStr.isNotBlank()) {
            try {
                updateSavePathLabel(Uri.parse(uriStr))
            } catch (_: Exception) {
                tvSavePath.text = "Ruta configurada"
            }
        } else {
            tvSavePath.text = "Sin ruta configurada — toca ⚙ para configurar"
        }

        refreshHistory(rv)
        offerReceiver.fragment = this
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(offerReceiver, IntentFilter(OfferLogger.ACTION_OFFER_LOGGED))
    }

    private fun updateSavePathLabel(uri: Uri) {
        try {
            val doc = DocumentFile.fromTreeUri(requireContext(), uri)
            tvSavePath.text = "📁 ${doc?.name ?: uri.lastPathSegment ?: "Carpeta seleccionada"}"
        } catch (_: Exception) {
            tvSavePath.text = "📁 Carpeta seleccionada"
        }
    }

    override fun onDestroyView() {
        refreshHandler.removeCallbacks(refreshRunnable)
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(offerReceiver)
        } catch (_: Exception) {
        }
        super.onDestroyView()
    }

    fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 350L)
    }

    private fun shareFile(path: String, mimeType: String, chooserTitle: String) {
        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(context, "Sin registros aún", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    chooserTitle,
                ),
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshHistory(rv: RecyclerView) {
        val log = logger ?: return
        Thread {
            val entries = log.getRecentEntries(100)
            val stats = log.getTodayStats()
            refreshHandler.post {
                if (!isAdded) return@post
                tvHistAccepted.text = stats.accepted.toString()
                tvHistRejected.text = stats.rejected.toString()
                tvHistCancelled.text = stats.cancelled.toString()
                tvHistSimulated.text = stats.simulated.toString()
                tvHistMiss.text = stats.miss.toString()
                tvHistSeen.text = stats.seen.toString()
                adapter = OfferLogAdapter(entries)
                rv.adapter = adapter
                val empty = entries.isEmpty()
                tvHistEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
                rv.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }.start()
    }
}

class OfferBroadcastReceiver : BroadcastReceiver() {
    var fragment: FlexHistorialFragment? = null
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OfferLogger.ACTION_OFFER_LOGGED) {
            fragment?.scheduleRefresh()
        }
    }
}

class OfferLogAdapter(private val items: List<OfferLogEntry>) :
    RecyclerView.Adapter<OfferLogAdapter.VH>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex: TextView = v.findViewById(R.id.tvOfferIndex)
        val tvStatus: TextView = v.findViewById(R.id.tvOfferStatus)
        val tvType: TextView = v.findViewById(R.id.tvOfferType)
        val tvDetail: TextView = v.findViewById(R.id.tvOfferDetail)
        val tvMeta: TextView = v.findViewById(R.id.tvOfferMeta)
        val tvReason: TextView = v.findViewById(R.id.tvOfferReason)
        val tvPrice: TextView = v.findViewById(R.id.tvOfferPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_offer_log, parent, false),
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val e = items[pos]
        holder.tvIndex.text = "#${pos + 1}"
        holder.tvDetail.text = e.station.ifBlank { "—" }
        holder.tvPrice.text = "$%.2f · %.1f h".format(e.price, e.durationHours)
        holder.tvStatus.text = OfferLogRowUi.statusIcon(e.status)
        holder.tvType.text = OfferLogRowUi.scheduleLabel(e)
        holder.tvMeta.text = OfferLogRowUi.metaRightLine(e, timeFmt)
        val reason = OfferLogRowUi.reasonLeft(e)
        holder.tvReason.text = reason
        holder.tvReason.visibility = if (reason.isBlank()) View.GONE else View.VISIBLE
    }
}
