package com.j.fmark

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.ClientFolderList
import com.j.fmark.fragments.ClientListFragment

class ClientAdapter(private var source : ClientFolderList, private val clientListFragment : ClientListFragment) : RecyclerView.Adapter<ClientAdapter.Holder>()
{
  class Holder(private val adapter : ClientAdapter, view : View) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnLongClickListener
  {
    var source : ClientFolder? = null
    val name : TextView = view.findViewById(R.id.client_name)
    val reading : TextView = view.findViewById(R.id.client_name_reading)
    init { view.setOnClickListener(this); view.setOnLongClickListener(this) }

    override fun onClick(v : View?)
    {
      val source = source ?: return
      adapter.clientListFragment.startClientEditor(source)
    }

    override fun onLongClick(v : View?) : Boolean
    {
      val source = source ?: return false
      adapter.clientListFragment.showClientDetails(source)
      return true
    }
  }

  fun setSource(s : ClientFolderList)
  {
    source = s
    notifyDataSetChanged()
  }

  override fun getItemCount() : Int = source.count
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : Holder = Holder(this, LayoutInflater.from(parent.context).inflate(R.layout.client_view, parent, false))
  override fun onBindViewHolder(holder : Holder, position : Int)
  {
    val source = source[position]
    holder.source = source
    holder.name.text = source.name
    holder.reading.text = source.reading
  }

  fun notifyRenamed(clientFolder : ClientFolder) = notifyItemChanged(source.findIndex(clientFolder))
}
