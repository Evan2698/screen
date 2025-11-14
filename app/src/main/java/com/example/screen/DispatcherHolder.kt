package com.example.screen

object DispatcherHolder{

    private var dispatcher : Dispatcher? = null

    fun dispatchTouchCommand(action : String){
        dispatcher!!.dispatch(action)
    }

    fun register(dispatcher : Dispatcher){
        this.dispatcher = dispatcher
    }

    fun unregister(){
        dispatcher = null
    }
}
