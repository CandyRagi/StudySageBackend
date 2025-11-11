package com.studysage.services

import com.studysage.models.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object GameManager {
    private val games = ConcurrentHashMap<String, Game>()
    private val gameTimers = ConcurrentHashMap<String, Job>()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun createGame(request: CreateGameRequest): ApiResponse<Game> {
        if (games.containsKey(request.gameId)) {
            return ApiResponse(false, "Game ID already exists")
        }

        if (request.questions.size != 10) {
            return ApiResponse(false, "Game must have exactly 10 questions")
        }

        val game = Game(
            gameId = request.gameId,
            hostId = request.hostId,
            password = request.password,
            questions = request.questions,
            maxPlayers = request.maxPlayers
        )

        games[request.gameId] = game
        return ApiResponse(true, "Game created successfully", game)
    }

    fun joinGame(request: JoinGameRequest): ApiResponse<Game> {
        val game = games[request.gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.password != request.password) {
            return ApiResponse(false, "Incorrect password")
        }

        if (game.players.size >= game.maxPlayers) {
            return ApiResponse(false, "Game is full")
        }

        if (game.status != GameStatus.WAITING) {
            return ApiResponse(false, "Game already started")
        }

        if (game.players.containsKey(request.playerId)) {
            return ApiResponse(false, "Player already in game")
        }

        val player = Player(
            id = request.playerId,
            name = request.playerName
        )

        game.players[request.playerId] = player
        return ApiResponse(true, "Joined game successfully", game)
    }

    fun startGame(gameId: String, hostId: String): ApiResponse<GameStateUpdate> {
        val game = games[gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.hostId != hostId) {
            return ApiResponse(false, "Only host can start the game")
        }

        if (game.players.isEmpty()) {
            return ApiResponse(false, "No players in game")
        }

        game.status = GameStatus.IN_PROGRESS
        game.startTime = System.currentTimeMillis()

        // Start the game timer
        startGameTimer(gameId)

        val update = GameStateUpdate(
            type = "GAME_STARTED",
            gameId = gameId,
            status = game.status,
            players = getPlayerScores(game),
            remainingTime = game.totalTimeLimit,
            message = "Game started! You have 10 minutes to complete all questions."
        )

        return ApiResponse(true, "Game started", update)
    }

    private fun startGameTimer(gameId: String) {
        // Cancel existing timer if any
        gameTimers[gameId]?.cancel()

        val timerJob = coroutineScope.launch {
            val game = games[gameId] ?: return@launch

            // Send time updates every 10 seconds
            while (isActive && game.status != GameStatus.FINISHED) {
                delay(10000) // 10 seconds

                val remainingTime = game.getRemainingTime()

                if (game.isTimeUp()) {
                    // Time's up! End the game
                    forceEndGame(gameId)
                    break
                } else {
                    // Send time update
                    val update = GameStateUpdate(
                        type = "TIME_UPDATE",
                        gameId = gameId,
                        status = game.status,
                        currentQuestionIndex = game.currentQuestionIndex,
                        players = getPlayerScores(game),
                        remainingTime = remainingTime
                    )
                    coroutineScope.launch {
                        WebSocketManager.broadcast(gameId, update)
                    }
                }
            }
        }

        gameTimers[gameId] = timerJob
    }

    private suspend fun forceEndGame(gameId: String) {
        val game = games[gameId] ?: return
        game.status = GameStatus.FINISHED

        val update = GameStateUpdate(
            type = "GAME_ENDED",
            gameId = gameId,
            status = game.status,
            players = getPlayerScores(game),
            remainingTime = 0,
            message = "Time's up! Game ended."
        )

        WebSocketManager.broadcast(gameId, update)
        gameTimers[gameId]?.cancel()
        gameTimers.remove(gameId)
    }

    fun startQuestion(gameId: String, hostId: String): ApiResponse<GameStateUpdate> {
        val game = games[gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.hostId != hostId) {
            return ApiResponse(false, "Only host can start questions")
        }

        if (game.isTimeUp()) {
            return endGame(gameId)
        }

        if (game.status != GameStatus.IN_PROGRESS && game.status != GameStatus.QUESTION_ENDED) {
            return ApiResponse(false, "Invalid game state")
        }

        game.currentQuestionIndex++

        if (game.currentQuestionIndex >= game.questions.size) {
            return endGame(gameId)
        }

        game.status = GameStatus.QUESTION_ACTIVE
        game.questionStartTime = System.currentTimeMillis()

        val currentQuestion = game.questions[game.currentQuestionIndex]

        val update = GameStateUpdate(
            type = "QUESTION_STARTED",
            gameId = gameId,
            status = game.status,
            currentQuestionIndex = game.currentQuestionIndex,
            question = currentQuestion,
            players = getPlayerScores(game),
            remainingTime = game.getRemainingTime()
        )

        return ApiResponse(true, "Question started", update)
    }

    fun submitAnswer(request: SubmitAnswerRequest): ApiResponse<PlayerAnswer> {
        val game = games[request.gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.isTimeUp()) {
            return ApiResponse(false, "Game time expired")
        }

        if (game.status != GameStatus.QUESTION_ACTIVE) {
            return ApiResponse(false, "No active question")
        }

        if (request.questionIndex != game.currentQuestionIndex) {
            return ApiResponse(false, "Invalid question index")
        }

        val player = game.players[request.playerId]
            ?: return ApiResponse(false, "Player not in game")

        if (player.answers.containsKey(request.questionIndex)) {
            return ApiResponse(false, "Already answered this question")
        }

        val question = game.questions[request.questionIndex]
        val isCorrect = request.answer == question.correctAnswer

        // Calculate points: base points + speed bonus
        // Speed bonus: faster answers get more points (up to 50 bonus points)
        val questionTime = System.currentTimeMillis() - game.questionStartTime
        val speedBonus = maxOf(0, 50 - (questionTime / 1000).toInt()) // Lose 1 point per second
        val points = if (isCorrect) 100 + speedBonus else 0

        val playerAnswer = PlayerAnswer(
            answer = request.answer,
            isCorrect = isCorrect,
            timeTaken = request.timeTaken,
            points = points
        )

        player.answers[request.questionIndex] = playerAnswer
        player.answeredQuestions++
        player.totalTimeTaken += request.timeTaken

        if (isCorrect) {
            player.correctAnswers++
            player.score += points
        }

        return ApiResponse(true, "Answer submitted", playerAnswer)
    }

    fun endQuestion(gameId: String, hostId: String): ApiResponse<GameStateUpdate> {
        val game = games[gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.hostId != hostId) {
            return ApiResponse(false, "Only host can end questions")
        }

        game.status = GameStatus.QUESTION_ENDED

        val update = GameStateUpdate(
            type = "QUESTION_ENDED",
            gameId = gameId,
            status = game.status,
            currentQuestionIndex = game.currentQuestionIndex,
            players = getPlayerScores(game),
            remainingTime = game.getRemainingTime()
        )

        return ApiResponse(true, "Question ended", update)
    }

    private fun endGame(gameId: String): ApiResponse<GameStateUpdate> {
        val game = games[gameId] ?: return ApiResponse(false, "Game not found")

        game.status = GameStatus.FINISHED

        // Cancel the timer
        gameTimers[gameId]?.cancel()
        gameTimers.remove(gameId)

        val update = GameStateUpdate(
            type = "GAME_ENDED",
            gameId = gameId,
            status = game.status,
            players = getPlayerScores(game),
            remainingTime = game.getRemainingTime(),
            message = "All questions completed!"
        )

        return ApiResponse(true, "Game ended", update)
    }

    fun getGameResults(gameId: String): ApiResponse<GameResult> {
        val game = games[gameId]
            ?: return ApiResponse(false, "Game not found")

        if (game.status != GameStatus.FINISHED) {
            return ApiResponse(false, "Game not finished yet")
        }

        val sortedPlayers = game.players.values.sortedWith(
            compareByDescending<Player> { it.score }
                .thenBy { it.totalTimeTaken }
        )

        val rankings = sortedPlayers.mapIndexed { index, player ->
            PlayerRank(
                rank = index + 1,
                player = player,
                accuracy = if (player.answeredQuestions > 0)
                    (player.correctAnswers.toDouble() / player.answeredQuestions) * 100
                else 0.0,
                averageTime = if (player.answeredQuestions > 0)
                    player.totalTimeTaken.toDouble() / player.answeredQuestions
                else 0.0
            )
        }

        val result = GameResult(
            gameId = gameId,
            rankings = rankings,
            totalQuestions = game.questions.size,
            gameStats = GameStats(
                totalPlayers = game.players.size,
                gameCompletedAt = System.currentTimeMillis(),
                gameDuration = System.currentTimeMillis() - game.startTime
            )
        )

        return ApiResponse(true, "Results retrieved", result)
    }

    fun getGame(gameId: String): Game? = games[gameId]

    fun deleteGame(gameId: String) {
        gameTimers[gameId]?.cancel()
        gameTimers.remove(gameId)
        games.remove(gameId)
    }

    private fun getPlayerScores(game: Game): List<PlayerScore> {
        return game.players.values.map {
            PlayerScore(
                id = it.id,
                name = it.name,
                score = it.score,
                correctAnswers = it.correctAnswers,
                answeredQuestions = it.answeredQuestions
            )
        }.sortedByDescending { it.score }
    }
}