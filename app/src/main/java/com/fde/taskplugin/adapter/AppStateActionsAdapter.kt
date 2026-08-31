package com.fde.taskplugin.adapter

import android.content.Context
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.fde.taskplugin.data.Action
import com.fde.taskplugin.R
import com.fde.taskplugin.utils.SystemuiColorUtils

class AppStateActionsAdapter(private val context: Context, actions: ArrayList<Action?>?) :
    ArrayAdapter<Action?>(
        context, R.layout.pin_entry, actions!!
    ) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val action = getItem(position)
        if (convertView == null) convertView =
            LayoutInflater.from(context).inflate(R.layout.pin_entry, null)
        val icon = convertView!!.findViewById<ImageView>(R.id.pin_entry_iv)
        val text = convertView.findViewById<TextView>(R.id.pin_entry_tv)
//        SystemuiColorUtils.applySecondaryColor(
//            context, PreferenceManager.getDefaultSharedPreferences(
//                context
//            ), icon
//        )
        text?.text = action!!.text
        if(action?.icon == 0){
            icon?.visibility = View.GONE
        }else{
            icon?.visibility = View.VISIBLE
            icon?.setImageResource(action!!.icon)
        }
        return convertView
    }
}