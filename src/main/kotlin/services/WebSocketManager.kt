package com.studysage.services

import com.studysage.models.GameStateUpdate
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

object WebSocketManager {
    private val connections = ConcurrentHashMap<String, MutableMap<String, WebSocketSession>>()
    private val json = Json { encodeDefaults = true }

    fun addConnection(gameId: String, playerId: String, session: WebSocketSession) {
        connections.getOrPut(gameId) { ConcurrentHashMap() }[playerId] = session
        println("Player $playerId connected to game $gameId. Total players: ${connections[gameId]?.size}")
    }

    fun removeConnection(gameId: String, playerId: String) {
        connections[gameId]?.remove(playerId)
        if (connections[gameId]?.isEmpty() == true) {
            connections.remove(gameId)
        }
        println("Player $playerId disconnected from game $gameId")
    }

    suspend fun broadcast(gameId: String, update: GameStateUpdate) {
        val gameSessions = connections[gameId] ?: return
        val message = json.encodeToString(update)

        gameSessions.values.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                println("Error broadcasting to session: ${e.message}")
            }
        }
    }

    suspend fun sendToPlayer(gameId: String, playerId: String, update: GameStateUpdate) {
        val session = connections[gameId]?.get(playerId) ?: return
        val message = json.encodeToString(update)

        try {
            session.send(Frame.Text(message))
        } catch (e: Exception) {
            println("Error sending to player $playerId: ${e.message}")
        }
    }
}