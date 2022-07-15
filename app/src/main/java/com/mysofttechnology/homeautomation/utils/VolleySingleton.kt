package com.mysofttechnology.homeautomation.utils

import android.content.Context
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley

class VolleySingleton constructor(context: Context) {
    companion object {
        /*
        @Volatile: meaning that writes to this field are immediately made visible to other threads.
         */
        @Volatile
        private var instance: VolleySingleton? = null
        fun getInstance(context: Context) = instance?: synchronized(this) {
            instance?: VolleySingleton(context)
        }
    }

    /*
    by lazy: requestQueue won't be initialized until this method gets called
     */
    val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(context.applicationContext)
    }

    fun <T> addToRequestQueue(req: Request<T>) {
        requestQueue.add(req)
    }
}