package com.studysage.routes

import com.studysage.models.*
import com.studysage.services.GameManager
import com.studysage.services.WebSocketManager
import kotlinx.coroutines.launch
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.gameRoutes() {
    route("/api/game") {

        // Create a new game
        post("/create") {
            try {
                val request = call.receive<CreateGameRequest>()
                val response = GameManager.createGame(request)

                if (response.success) {
                    call.respond(HttpStatusCode.Created, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error creating game: ${e.message}")
                )
            }
        }

        // Join an existing game
        post("/join") {
            try {
                val request = call.receive<JoinGameRequest>()
                val response = GameManager.joinGame(request)

                if (response.success) {
                    // Notify all players in the game
                    val game = response.data!!
                    val update = GameStateUpdate(
                        type = "PLAYER_JOINED",
                        gameId = game.gameId,
                        status = game.status,
                        players = game.players.values.map {
                            PlayerScore(it.id, it.name, it.score, it.correctAnswers, it.answeredQuestions)
                        },
                        message = "${request.playerName} joined the game"
                    )
                    WebSocketManager.broadcast(game.gameId, update)

                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error joining game: ${e.message}")
                )
            }
        }

        // Start the game (host only)
        post("/start") {
            try {
                val gameId = call.request.queryParameters["gameId"]
                val hostId = call.request.queryParameters["hostId"]

                if (gameId == null || hostId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Unit>(false, "Missing gameId or hostId")
                    )
                    return@post
                }

                val response = GameManager.startGame(gameId, hostId)

                if (response.success) {
                    WebSocketManager.broadcast(gameId, response.data!!)
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error starting game: ${e.message}")
                )
            }
        }

        // Start a question (host only)
        post("/question/start") {
            try {
                val gameId = call.request.queryParameters["gameId"]
                val hostId = call.request.queryParameters["hostId"]

                if (gameId == null || hostId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Unit>(false, "Missing gameId or hostId")
                    )
                    return@post
                }

                val response = GameManager.startQuestion(gameId, hostId)

                if (response.success) {
                    WebSocketManager.broadcast(gameId, response.data!!)
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error starting question: ${e.message}")
                )
            }
        }

        // Submit an answer
        post("/answer") {
            try {
                val request = call.receive<SubmitAnswerRequest>()
                val response = GameManager.submitAnswer(request)

                if (response.success) {
                    // Notify all players that someone answered
                    val game = GameManager.getGame(request.gameId)
                    if (game != null) {
                        val update = GameStateUpdate(
                            type = "ANSWER_SUBMITTED",
                            gameId = game.gameId,
                            status = game.status,
                            currentQuestionIndex = game.currentQuestionIndex,
                            players = game.players.values.map {
                                PlayerScore(it.id, it.name, it.score, it.correctAnswers, it.answeredQuestions)
                            }.sortedByDescending { it.score }
                        )
                        WebSocketManager.broadcast(game.gameId, update)
                    }

                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error submitting answer: ${e.message}")
                )
            }
        }

        // End a question (host only)
        post("/question/end") {
            try {
                val gameId = call.request.queryParameters["gameId"]
                val hostId = call.request.queryParameters["hostId"]

                if (gameId == null || hostId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Unit>(false, "Missing gameId or hostId")
                    )
                    return@post
                }

                val response = GameManager.endQuestion(gameId, hostId)

                if (response.success) {
                    WebSocketManager.broadcast(gameId, response.data!!)
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error ending question: ${e.message}")
                )
            }
        }

        // Get game results
        get("/results/{gameId}") {
            try {
                val gameId = call.parameters["gameId"]

                if (gameId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Unit>(false, "Missing gameId")
                    )
                    return@get
                }

                val response = GameManager.getGameResults(gameId)

                if (response.success) {
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.BadRequest, response)
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error getting results: ${e.message}")
                )
            }
        }

        // Get current game state
        get("/{gameId}") {
            try {
                val gameId = call.parameters["gameId"]

                if (gameId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<Unit>(false, "Missing gameId")
                    )
                    return@get
                }

                val game = GameManager.getGame(gameId)

                if (game != null) {
                    call.respond(HttpStatusCode.OK, ApiResponse(true, data = game))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse<Unit>(false, "Game not found")
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<Unit>(false, "Error getting game: ${e.message}")
                )
            }
        }

        // WebSocket endpoint for real-time updates
        webSocket("/ws/{gameId}") {
            val gameId = call.parameters["gameId"] ?: return@webSocket close(
                CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing gameId")
            )

            val playerId = call.request.queryParameters["playerId"] ?: return@webSocket close(
                CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing playerId")
            )

            try {
                WebSocketManager.addConnection(gameId, playerId, this)

                // Send confirmation
                send(Frame.Text(Json.encodeToString(
                    ApiResponse(true, "Connected to game", null)
                )))

                // Listen for client messages
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        println("Received from $playerId: $text")
                    }
                }
            } catch (e: Exception) {
                println("WebSocket error: ${e.message}")
            } finally {
                WebSocketManager.removeConnection(gameId, playerId)
            }
        }
    }
}