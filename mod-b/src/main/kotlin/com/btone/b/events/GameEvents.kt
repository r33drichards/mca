package com.btone.b.events

import baritone.api.BaritoneAPI
import baritone.api.event.events.PathEvent
import baritone.api.event.listener.AbstractGameEventListener
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

object GameEvents {
    fun register(bus: EventBus) {
        ClientReceiveMessageEvents.GAME.register { msg, _ ->
            bus.emit("chat", mapOf("text" to msg.string))
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            bus.emit("joined")
            // Register baritone listener AFTER player joins — primaryBaritone is null at init.
            registerBaritonePathListener(bus)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> bus.emit("disconnected") }
    }

    private fun registerBaritonePathListener(bus: EventBus) {
        try {
            val b = BaritoneAPI.getProvider().primaryBaritone ?: return
            b.gameEventHandler.registerEventListener(object : AbstractGameEventListener {
                override fun onPathEvent(event: PathEvent) {
                    bus.emit("path", mapOf("event" to event.name))
                }
            })
        } catch (_: Throwable) {}
    }
}
