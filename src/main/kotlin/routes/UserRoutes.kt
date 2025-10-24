package com.group_7.routes

import com.group_7.models.CreateUserRequest
import com.group_7.models.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

val users = mutableListOf<User>()
var currentId = 1

fun Route.userRoutes() {
    route("/users") {
        get {
            call.respond(HttpStatusCode.OK, users)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID")
                return@get
            }

            val user = users.find { it.id == id }
            if (user == null) {
                call.respond(HttpStatusCode.NotFound, "User not found")
            } else {
                call.respond(HttpStatusCode.OK, user)
            }
        }

        post {
            val request = call.receive<CreateUserRequest>()
            val newUser = User(
                id = currentId++,
                name = request.name,
                email = request.email
            )
            users.add(newUser)
            call.respond(HttpStatusCode.Created, newUser)
        }
    }
}