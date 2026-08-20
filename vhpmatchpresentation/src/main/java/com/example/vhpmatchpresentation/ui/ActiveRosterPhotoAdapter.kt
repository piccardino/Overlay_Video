package com.example.vhpmatchpresentation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vhpmatchpresentation.R
import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import com.example.vhpmatchpresentation.data.PlayerPresentation
import com.example.vhpmatchpresentation.util.ImageHelper

class ActiveRosterPhotoAdapter(
    private var players: List<PlayerPresentation>,
    private val photoManager: PhotoMatchingManager? = null,
    private val teamColorHex: String = "",
    private val onSelectRedPhoto: (PlayerPresentation) -> Unit,
    private val onSelectBluePhoto: (PlayerPresentation) -> Unit,
    private val onRemovePhoto: (PlayerPresentation) -> Unit = {}
) : RecyclerView.Adapter<ActiveRosterPhotoAdapter.ViewHolder>() {

    fun updatePlayers(newPlayers: List<PlayerPresentation>) {
        this.players = newPlayers
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgPhoto: ImageView = itemView.findViewById(R.id.imgItemPhoto)
        val txtName: TextView = itemView.findViewById(R.id.txtItemPlayerName)
        val txtRole: TextView = itemView.findViewById(R.id.txtItemPlayerRole)
        val btnRed: Button = itemView.findViewById(R.id.btnItemRedPhoto)
        val btnBlue: Button = itemView.findViewById(R.id.btnItemBluePhoto)
        val btnRemove: View = itemView.findViewById(R.id.btnItemRemovePhoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_active_roster_player_photo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val player = players[position]
        holder.txtName.text = "#${player.number} ${player.name}"
        val statsSummary = "ATK:${player.stats.attack} BLK:${player.stats.block} SRV:${player.stats.serve}"
        holder.txtRole.text = "${player.role}  |  $statsSummary"

        val photo = player.photoUri.takeIf { !it.isNullOrEmpty() }
            ?: photoManager?.getPhotoUriForPlayer(player.name, teamColorHex, player.number).orEmpty()

        ImageHelper.loadPlayerPhoto(holder.itemView.context, holder.imgPhoto, photo)

        holder.btnRed.setOnClickListener {
            onSelectRedPhoto(player)
        }
        holder.btnBlue.setOnClickListener {
            onSelectBluePhoto(player)
        }
        holder.btnRemove.setOnClickListener {
            onRemovePhoto(player)
        }
    }

    override fun getItemCount(): Int = players.size
}
