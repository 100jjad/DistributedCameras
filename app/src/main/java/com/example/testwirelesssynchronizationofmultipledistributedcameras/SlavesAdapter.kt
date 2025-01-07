package com.example.testwirelesssynchronizationofmultipledistributedcameras
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class SlavesAdapter(context: Context, private val slaves: List<String>) :
    ArrayAdapter<String>(context, 0, slaves) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        textView.text = slaves[position]
        return view
    }
}