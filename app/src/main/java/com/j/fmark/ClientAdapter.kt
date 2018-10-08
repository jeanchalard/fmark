package com.j.fmark

import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.gms.drive.MetadataBuffer

class ClientAdapter(source : MetadataBuffer) : RecyclerView.Adapter<ClientAdapter.Holder>()
{
  private var source : MetadataBuffer = source

  class Holder(val view : View) : RecyclerView.ViewHolder(view)
  {
    val name : TextView = view.findViewById(R.id.client_name)
    val reading : TextView = view.findViewById(R.id.client_name_reading)
  }

  fun setSource(s : MetadataBuffer)
  {
    source = s
    notifyDataSetChanged()
  }

  override fun getItemCount() : Int = source.count
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : ClientAdapter.Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.client_view, null))
  override fun onBindViewHolder(holder : ClientAdapter.Holder, position : Int)
  {
    val fileName = source[position].title
    holder.name.text = fileName.substring(0..0)
    holder.reading.text = fileName.substring(1..1)
  }
}
