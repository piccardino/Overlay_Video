package com.example.vhpmatchpresentation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.vhpmatchpresentation.R
import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import com.example.vhpmatchpresentation.data.PlayerPresentation

class PhotoMappingAdapter(
    private var players: List<PlayerPresentation>,
    private val photoManager: PhotoMatchingManager? = null
) : RecyclerView.Adapter<PhotoMappingAdapter.ViewHolder>() {

    fun updatePlayers(newPlayers: List<PlayerPresentation>) {
        this.players = newPlayers
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgPhoto: ImageView = itemView.findViewById(R.id.imgItemPhoto)
        val txtName: TextView = itemView.findViewById(R.id.txtItemPlayerName)
        val txtRole: TextView = itemView.findViewById(R.id.txtItemPlayerRole)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player_photo_mapping, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val player = players[position]
        holder.txtName.text = "#${player.number} ${player.name}"
        holder.txtRole.text = player.role

        val photo = player.photoUri.takeIf { !it.isNullOrEmpty() }
            ?: photoManager?.getPhotoUriForPlayer(player.name, "", player.number).orEmpty()

        if (photo.isNotEmpty()) {
            holder.imgPhoto.load(photo) {
                placeholder(R.drawable.ic_player_silhouette)
                error(R.drawable.ic_player_silhouette)
            }
        } else {
            holder.imgPhoto.setImageResource(R.drawable.ic_player_silhouette)
        }
    }

    override fun getItemCount(): Int = players.size
}