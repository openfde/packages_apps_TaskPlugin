package com.fde.taskplugin

import android.graphics.drawable.Drawable
import com.fde.taskplugin.data.App

class AppTask(val iD: Int, label: String?, packageName: String?, icon: Drawable?) :
    App(label!!, packageName, icon)