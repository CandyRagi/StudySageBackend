package com.studysage.models

import kotlinx.serialization.Serializable

@Serializable
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctAnswer: Int // index of correct option
)

@Serializable
data class CreateGameRequest(
    val hostId: String,
    val gameId: String,
    val password: String,
    val questions: List<QuizQuestion>,
    val maxPlayers: Int = 4
) {
    init {
        require(questions.size == 10) { "Game must have exactly 10 questions" }
    }
}

@Serializable
data class JoinGameRequest(
    val playerId: String,
    val playerName: String,
    val gameId: String,
    val password: String
)

@Serializable
data class SubmitAnswerRequest(
    val playerId: String,
    val gameId: String,
    val questionIndex: Int,
    val answer: Int,
    val timeTaken: Int // in milliseconds
)

@Serializable
data class Player(
    val id: String,
    val name: String,
    var score: Int = 0,
    var answeredQuestions: Int = 0,
    var correctAnswers: Int = 0,
    var totalTimeTaken: Long = 0,
    val answers: MutableMap<Int, PlayerAnswer> = mutableMapOf()
)

@Serializable
data class PlayerAnswer(
    val answer: Int,
    val isCorrect: Boolean,
    val timeTaken: Int,
    val points: Int
)

@Serializable
data class Game(
    val gameId: String,
    val hostId: String,
    val password: String,
    val questions: List<QuizQuestion>,
    val maxPlayers: Int,
    val players: MutableMap<String, Player> = mutableMapOf(),
    var currentQuestionIndex: Int = -1,
    var status: GameStatus = GameStatus.WAITING,
    var startTime: Long = 0,
    var questionStartTime: Long = 0,
    val totalTimeLimit: Long = 600000 // 10 minutes in milliseconds
) {
    fun getRemainingTime(): Long {
        if (status == GameStatus.WAITING || status == GameStatus.FINISHED) return totalTimeLimit
        val elapsed = System.currentTimeMillis() - startTime
        return maxOf(0, totalTimeLimit - elapsed)
    }

    fun isTimeUp(): Boolean {
        if (status == GameStatus.WAITING || status == GameStatus.FINISHED) return false
        return getRemainingTime() <= 0
    }
}

@Serializable
enum class GameStatus {
    WAITING,
    IN_PROGRESS,
    QUESTION_ACTIVE,
    QUESTION_ENDED,
    FINISHED
}

@Serializable
data class GameStateUpdate(
    val type: String, // "PLAYER_JOINED", "GAME_STARTED", "QUESTION_STARTED", "ANSWER_SUBMITTED", "QUESTION_ENDED", "GAME_ENDED", "TIME_UPDATE"
    val gameId: String,
    val status: GameStatus,
    val currentQuestionIndex: Int? = null,
    val question: QuizQuestion? = null,
    val players: List<PlayerScore>? = null,
    val message: String? = null,
    val remainingTime: Long? = null // in milliseconds
)

@Serializable
data class PlayerScore(
    val id: String,
    val name: String,
    val score: Int,
    val correctAnswers: Int,
    val answeredQuestions: Int
)

@Serializable
data class GameResult(
    val gameId: String,
    val rankings: List<PlayerRank>,
    val totalQuestions: Int,
    val gameStats: GameStats
)

@Serializable
data class PlayerRank(
    val rank: Int,
    val player: Player,
    val accuracy: Double,
    val averageTime: Double
)

@Serializable
data class GameStats(
    val totalPlayers: Int,
    val gameCompletedAt: Long,
    val gameDuration: Long
)

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null
)