package de.radiowidget

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class StationManagerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Tabs
    private lateinit var tabMy: TextView
    private lateinit var tabSearch: TextView
    private lateinit var panelMy: LinearLayout
    private lateinit var panelSearch: LinearLayout

    // My stations – RecyclerView with drag support
    private lateinit var recycler: RecyclerView
    private lateinit var stationAdapter: StationAdapter
    private val myStations = mutableListOf<RadioStation>()

    // Search
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchList: ListView
    private lateinit var searchAdapter: ArrayAdapter<String>
    private val searchResults = mutableListOf<SearchResult>()

    // ItemTouchHelper for drag-and-drop
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_station_manager)
        supportActionBar?.title = "Sender verwalten"

        tabMy       = findViewById(R.id.tab_my)
        tabSearch   = findViewById(R.id.tab_search)
        panelMy     = findViewById(R.id.panel_my)
        panelSearch = findViewById(R.id.panel_search)

        recycler       = findViewById(R.id.recycler_my_stations)
        searchInput    = findViewById(R.id.search_input)
        btnSearch      = findViewById(R.id.btn_search)
        searchProgress = findViewById(R.id.search_progress)
        searchList     = findViewById(R.id.list_search_results)

        setupRecycler()
        loadMyStations()
        setupTabs()
        setupSearch()
    }

    private fun setupRecycler() {
        stationAdapter = StationAdapter(myStations,
            onDragStart = { vh -> itemTouchHelper.startDrag(vh) },
            onDelete    = { pos ->
                val s = myStations[pos]
                AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setMessage("Sender entfernen?")
                    .setPositiveButton("Entfernen") { _, _ ->
                        myStations.removeAt(pos)
                        stationAdapter.notifyItemRemoved(pos)
                        persist()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        )

        recycler.apply {
            layoutManager = LinearLayoutManager(this@StationManagerActivity)
            adapter = stationAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to   = target.adapterPosition
                myStations.add(to, myStations.removeAt(from))
                stationAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                persist()   // save order after drag ends
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, actionState: Int, isActive: Boolean) {
                vh.itemView.alpha = if (isActive) 0.85f else 1f
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recycler)
    }

    private fun loadMyStations() {
        myStations.clear()
        myStations.addAll(StationRepository.getAllVisible(this))
        stationAdapter.notifyDataSetChanged()
    }

    private fun persist() {
        StationRepository.saveAll(this, myStations)
        RadioWidgetProvider.updateAllWidgets(this)
    }

    private fun setupTabs() {
        tabMy.setOnClickListener {
            panelMy.visibility = View.VISIBLE
            panelSearch.visibility = View.GONE
            tabMy.alpha = 1f; tabSearch.alpha = 0.5f
        }
        tabSearch.setOnClickListener {
            panelMy.visibility = View.GONE
            panelSearch.visibility = View.VISIBLE
            tabSearch.alpha = 1f; tabMy.alpha = 0.5f
        }
        tabMy.alpha = 1f; tabSearch.alpha = 0.5f
    }

    private fun setupSearch() {
        searchAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        searchList.adapter = searchAdapter

        btnSearch.setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.length < 2) {
                Toast.makeText(this, "Mindestens 2 Zeichen eingeben", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runSearch(q)
        }
        searchInput.setOnEditorActionListener { _, _, _ -> btnSearch.performClick(); true }

        searchList.setOnItemClickListener { _, _, pos, _ ->
            val r = searchResults[pos]
            AlertDialog.Builder(this)
                .setTitle("\"${r.name}\" hinzufügen?")
                .setMessage(buildString {
                    append("Genre: ${r.genre.ifBlank { "–" }}\n")
                    append("Land: ${r.country.ifBlank { "–" }}\n")
                    append("Codec: ${r.codec}  ${if (r.bitrate > 0) "${r.bitrate} kbps" else ""}")
                })
                .setPositiveButton("Hinzufügen") { _, _ ->
                    StationRepository.addCustom(this, RadioStation(
                        name      = r.name,
                        shortName = r.name.take(3).uppercase(),
                        frequency = if (r.bitrate > 0) "${r.bitrate} kbps" else "Stream",
                        genre     = r.genre,
                        streamUrl = r.streamUrl,
                        isCustom  = true
                    ))
                    loadMyStations()
                    RadioWidgetProvider.updateAllWidgets(this)
                    Toast.makeText(this, "${r.name} hinzugefügt", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun runSearch(query: String) {
        searchProgress.visibility = View.VISIBLE
        btnSearch.isEnabled = false
        searchResults.clear()
        searchAdapter.clear()

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching { RadioBrowserApi.searchByName(query) }.getOrElse { emptyList() }
            }
            searchProgress.visibility = View.GONE
            btnSearch.isEnabled = true
            if (results.isEmpty()) {
                Toast.makeText(this@StationManagerActivity, "Keine Ergebnisse", Toast.LENGTH_SHORT).show()
                return@launch
            }
            searchResults.addAll(results)
            searchAdapter.clear()
            searchAdapter.addAll(results.map {
                "${it.name}  ${if (it.bitrate > 0) "• ${it.bitrate} kbps" else ""}  ${it.country}"
            })
            searchAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// ── RecyclerView Adapter with drag handle + delete button ──────────────────

class StationAdapter(
    private val items: MutableList<RadioStation>,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<StationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name:   TextView    = v.findViewById(R.id.item_name)
        val sub:    TextView    = v.findViewById(R.id.item_sub)
        val drag:   ImageView   = v.findViewById(R.id.item_drag)
        val delete: ImageButton = v.findViewById(R.id.item_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(vh: VH, position: Int) {
        val s = items[position]
        vh.name.text = s.name
        vh.sub.text  = buildString {
            append(s.genre)
            if (s.frequency.isNotBlank()) append("  •  ${s.frequency}")
            if (s.isCustom) append("  ★")
        }
        vh.drag.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(vh)
            false
        }
        vh.delete.setOnClickListener { onDelete(vh.adapterPosition) }
    }

    override fun getItemCount() = items.size
}
